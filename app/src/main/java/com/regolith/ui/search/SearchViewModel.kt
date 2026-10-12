package com.regolith.ui.search

import com.regolith.data.spoof.spoofed
import com.regolith.data.spoof.SpoofMode
import com.regolith.data.spoof.Spoof
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.regolith.data.db.FolderEntity
import com.regolith.data.prefs.AppPreferences
import com.regolith.data.db.MediaFileEntity
import com.regolith.data.db.ScanRunEntity
import com.regolith.data.repository.LibraryRepository
import com.regolith.data.repository.SourceRepository
import com.regolith.data.repository.UserChapterRepository
import com.regolith.data.scan.ScanRepository
import com.regolith.data.transfer.TransferRepository
import com.regolith.domain.library.ParsedName
import com.regolith.domain.library.ViewMode
import com.regolith.domain.playback.ChapterFacet
import com.regolith.domain.playback.ChapterMatch
import com.regolith.domain.playback.VideoInfo
import com.regolith.ui.util.formatBytes
import com.regolith.ui.util.formatClock
import com.regolith.ui.util.formatDurationShort
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import com.regolith.ui.util.formatFileCount
import com.regolith.domain.transfer.FilePick
import com.regolith.domain.transfer.FolderPick
import com.regolith.ui.util.FileActions
import com.regolith.ui.util.SelectionPresenter
import com.regolith.ui.util.SelectionUiState

enum class SearchFilter(val label: String) { ALL("All"), UNWATCHED("Unwatched"), UHD("4K"), ON_DEVICE("On device") }

/**
 * What is on this phone, for the On device chip: files with a finished
 * download, wherever their share is, and every file in "This device"'s own
 * shares — copies adopted from a disconnected server, and the videos the
 * phone already had ([com.regolith.domain.media.DeviceSource]).
 */
internal data class OnDevice(
    val doneFileIds: Set<Long> = emptySet(),
    val deviceShareIds: Set<Long> = emptySet(),
) {
    fun has(fileId: Long, shareId: Long): Boolean = fileId in doneFileIds || shareId in deviceShareIds
}

/**
 * What a search found: [files] and [folders], whether anything was asked
 * ([searched]), and whether a capped search found [more] than it holds.
 */
private data class Found(
    val files: List<MediaFileEntity>,
    val folders: List<FolderEntity>,
    val searched: Boolean,
    val more: Boolean = false,
)

/**
 * The points of interest to show, from [matches] (a chip's marks, or the
 * names the query found). With a [chip] they ARE the result, the moments
 * beside the films listed, so only those in films the filter chips [kept]:
 * one for one with the matches. Without one they show under All only: the
 * other filters are about the file (watched, 4K, on device), and a moment
 * is not a file.
 */
internal fun pointsOfInterest(matches: List<ChapterMatch>, chip: String?, filter: SearchFilter, kept: Set<Long>): List<ChapterMatch> = when {
    chip != null -> matches.filter { it.fileId in kept }
    filter == SearchFilter.ALL -> matches
    else -> emptyList()
}

/** Whether a file passes this chip, given what is known about it. */
internal fun SearchFilter.keeps(unwatched: Boolean, uhd: Boolean, onDevice: Boolean): Boolean = when (this) {
    SearchFilter.ALL -> true
    SearchFilter.UNWATCHED -> unwatched
    SearchFilter.UHD -> uhd
    SearchFilter.ON_DEVICE -> onDevice
}

/** One row of results. Titles, raw filenames and folders share the list. */
sealed interface SearchHit {
    val testTag: String
    val primary: String
    val meta: String

    /** What a download pick needs: which share, and where in it. */
    val shareId: Long
    val relPath: String

    /**
     * A video whose filename parsed into a recognisable title. The title is
     * the primary line and the filename rides in [meta]; a file is listed
     * as EITHER this or [File], never both. See the hits loop in
     * [SearchViewModel] for why.
     */
    data class Title(
        val fileId: Long,
        override val primary: String,
        override val meta: String,
        override val shareId: Long = 0,
        /** The folder holding the file. */
        override val relPath: String = "",
        val sizeBytes: Long = 0,
    ) : SearchHit {
        override val testTag get() = "search_title_$fileId"
    }

    /** A video whose name did not parse into a title, so the filename IS the row. */
    data class File(
        val fileId: Long,
        override val primary: String,
        override val meta: String,
        override val shareId: Long = 0,
        override val relPath: String = "",
        val sizeBytes: Long = 0,
    ) : SearchHit {
        override val testTag get() = "search_file_$fileId"
    }

    /**
     * A chapter the user named, matched by that name: a PLACE in a film
     * rather than a file. Never part of a download selection — the film
     * is what you would download, and its own row is there for that.
     */
    data class Moment(
        val fileId: Long,
        val startMs: Long,
        /** The chapter's name; the match is in here. */
        override val primary: String,
        /** The film's title and the time. */
        override val meta: String,
        override val shareId: Long = 0,
        override val relPath: String = "",
    ) : SearchHit {
        override val testTag get() = "search_moment_${fileId}_$startMs"
    }

    data class Folder(
        val folderId: Long,
        val browsable: Boolean,
        override val primary: String,
        override val meta: String,
        override val shareId: Long = 0,
        override val relPath: String = "",
        val fileCount: Int = 0,
        val byteCount: Long = 0,
        val listed: Boolean = true,
    ) : SearchHit {
        override val testTag get() = "search_folder_$folderId"
    }
}

data class SearchUiState(
    val query: String = "",
    val filter: SearchFilter = SearchFilter.ALL,
    /** Points of interest offered in the moment sheet: every named moment the library holds, commonest first. */
    val facets: List<ChapterFacet> = emptyList(),
    /** The chip currently on, if any. Narrows everything to films carrying it. */
    val poi: String? = null,
    val hits: List<SearchHit> = emptyList(),
    /** A typed search found more files or folders than it lists: the count reads "40+". */
    val moreMatches: Boolean = false,
    /**
     * Points of interest, shown above [hits]: with a chip, every mark of its
     * name in the films listed; otherwise chapters whose names match.
     */
    val moments: List<SearchHit.Moment> = emptyList(),
    /** A typed search found more chapter names than it lists. */
    val moreMoments: Boolean = false,
    val recent: List<String> = emptyList(),
    /** "Still reading /media/archive — matches will keep arriving." */
    val scanningPath: String? = null,
    /** The user has typed something and results have been computed for it. */
    val searched: Boolean = false,
    /** Non-null while a multi-selection is running. */
    val selection: SelectionUiState? = null,
    /** Rows or tiles, for both result groups at once; remembered (`AppPreferences.searchViewMode`). */
    val viewMode: ViewMode = ViewMode.ROWS,
)

/**
 * Search (design section 06): filenames, parsed titles and folders in one
 * list, matched as you type. Everything comes from Room's full-text
 * indexes, so results keep arriving while a scan is still walking.
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel(assistedFactory = SearchViewModel.Factory::class)
class SearchViewModel @AssistedInject constructor(
    /**
     * A moment to start on, from [com.regolith.ui.navigation.RegolithKey.Search]:
     * Home's Moments section opens Search with one already picked. Null is
     * the ordinary Search, opened from a search icon.
     */
    @Assisted initialPoi: String?,
    private val library: LibraryRepository,
    sources: SourceRepository,
    scans: ScanRepository,
    private val selection: SelectionPresenter,
    userChapters: UserChapterRepository,
    private val prefs: AppPreferences,
    private val spoof: SpoofMode,
    fileActionsFactory: FileActions.Factory,
    transfers: TransferRepository,
) : ViewModel() {

    @AssistedFactory
    interface Factory {
        fun create(poi: String?): SearchViewModel
    }

    /** Rename, move and delete for the picks, the same as Browse's and the Library's. */
    val fileActions: FileActions = fileActionsFactory.create(viewModelScope)

    private val query = MutableStateFlow("")
    private val filter = MutableStateFlow(SearchFilter.ALL)
    private val poi = MutableStateFlow(initialPoi)

    // The names themselves: free of the share, since the sidecar import put
    // every chapter name in the table when the folder was listed. Every one
    // of them, including those on a single film — see the DAO.
    //
    // Spoof mode offers none: a chip is a real chapter name, and the films it
    // finds are looked up by that name, so a made-up label could not find
    // anything. The query still reaches chapter names, made up on the way out.
    private val facets = combine(userChapters.facets(FACETS_LIMIT), spoof.state) { list, spoofed -> if (spoofed == null) list else emptyList() }

    /**
     * A point of interest narrows to the films carrying it, which is why it
     * stands in for the query when there is none: a chip on its own is a
     * search. Folders drop out while one is on — a folder has no chapters,
     * so it can never carry the thing being asked for.
     */
    private val results = combine(query.debounce(150), poi) { q, p -> q to p }.flatMapLatest { (q, p) ->
        when {
            // Every film carrying it, with any words typed narrowing them in
            // the same query. A chip is a filter, not a guess: no cap.
            p != null -> userChapters.filesWith(p, q).map { Found(it, emptyList(), searched = true) }
            q.isBlank() -> flowOf(Found(emptyList(), emptyList(), searched = false))
            // Typed: capped, with one more asked for than is shown, so the
            // screen can say there are more rather than stop at 40 silently.
            else -> combine(library.searchFiles(q, LIMIT + 1), library.searchFolders(q, LIMIT + 1)) { files, folders ->
                Found(files.take(LIMIT), folders.take(LIMIT), searched = true, more = files.size > LIMIT || folders.size > LIMIT)
            }
        }
    }.spoofed(spoof) { found -> found.copy(files = files(found.files), folders = folders(found.folders)) }
    private val progress = results.flatMapLatest { found -> library.observeProgress(found.files.map { it.id }) }
    // Chapter names. With a chip, every mark of its name in the films listed
    // below: the same question of the same rows, so the two groups always
    // hold the same films. Otherwise whatever the query matches, through the
    // same MATCH rule, capped as the files are and saying so.
    private val moments = combine(query.debounce(150), poi) { q, p -> q to p }.flatMapLatest { (q, p) ->
        when {
            p != null -> userChapters.occurrences(p, q).map { it to false }
            q.isBlank() -> flowOf(emptyList<ChapterMatch>() to false)
            else -> userChapters.search(q, MOMENTS_LIMIT + 1).map { it.take(MOMENTS_LIMIT) to (it.size > MOMENTS_LIMIT) }
        }
    }.spoofed(spoof) { (found, more) -> matches(found) to more }
    private val onDevice = combine(transfers.observeDoneFileIds(), sources.observeDeviceShareIds(), ::OnDevice)
    private val running = sources.observeEnabledShares().flatMapLatest { list ->
        if (list.isEmpty()) flowOf(emptyList()) else scans.observeLatest(list.map { it.id })
    }

    val uiState: StateFlow<SearchUiState> = combine(
        query, filter, results, progress, running, library.observeRecentSearches(8), selection.observe(), moments, facets, poi, prefs.searchViewMode, spoof.state, onDevice,
    ) { values ->
        val q = values[0] as String
        val f = values[1] as SearchFilter
        val result = values[2] as Found
        val files = result.files
        val folders = result.folders
        val searched = result.searched
        @Suppress("UNCHECKED_CAST")
        val progressById = (values[3] as List<com.regolith.data.db.PlaybackProgressEntity>).associateBy { it.fileId }
        @Suppress("UNCHECKED_CAST")
        val runs = values[4] as List<ScanRunEntity>
        @Suppress("UNCHECKED_CAST")
        val recent = (values[5] as List<com.regolith.data.db.RecentSearchEntity>).map { it.query }
        val sel = values[6] as SelectionUiState?
        @Suppress("UNCHECKED_CAST")
        val momentsFound = values[7] as Pair<List<ChapterMatch>, Boolean>
        val (matches, moreMoments) = momentsFound
        @Suppress("UNCHECKED_CAST")
        val facetList = values[8] as List<ChapterFacet>
        val chip = values[9] as String?
        val mode = values[10] as ViewMode
        // Paths shown as text are made up part by part; the real ones stay on the hits for picking.
        val spoofed = values[11] as Spoof?
        val here = values[12] as OnDevice
        fun shownDir(relPath: String): String = relPath.substringBeforeLast('/', "").let { spoofed?.path(it) ?: it }

        val hits = mutableListOf<SearchHit>()
        // The films the filter chips kept, which a chip's moments follow.
        val kept = HashSet<Long>()
        for (file in files) {
            val p = progressById[file.id]
            val unwatched = p == null || (p.positionMs == 0L && !p.completed)
            val uhd = VideoInfo.resolutionLabelFor(file.width, file.height) == "4K"
            if (!f.keeps(unwatched, uhd, here.has(file.id, file.shareId))) continue
            kept += file.id
            val parsed = ParsedName(file.titleParsed ?: file.name.substringBeforeLast('.'), file.year, file.season, file.episode)
            val res = VideoInfo.resolutionLabelFor(file.width, file.height).ifEmpty { null }
            // ONE ROW PER FILE. A file whose name parses into a title wears
            // the title and carries its filename in the meta line; one that
            // does not is its filename. Both open the same screen, so a
            // second row was never a second destination -- just the same
            // video listed twice under one heading, which made "12 matches"
            // mean six. The filename stays visible because it is often where
            // the match actually is, and the meta line is highlighted too so
            // a row can always say why it is here.
            if (parsed.matched) {
                hits += SearchHit.Title(
                    fileId = file.id,
                    primary = parsed.display,
                    meta = listOfNotNull(res, file.durationMs?.let { formatDurationShort(it) }, file.name).joinToString(" · "),
                    shareId = file.shareId,
                    relPath = file.relPath.substringBeforeLast('/', ""),
                    sizeBytes = file.sizeBytes,
                )
            } else {
                hits += SearchHit.File(
                    fileId = file.id,
                    primary = file.name,
                    meta = listOfNotNull(res, formatBytes(file.sizeBytes), "/" + shownDir(file.relPath)).joinToString(" · "),
                    shareId = file.shareId,
                    relPath = file.relPath.substringBeforeLast('/', ""),
                    sizeBytes = file.sizeBytes,
                )
            }
        }
        if (f == SearchFilter.ALL) {
            for (folder in folders) {
                hits += SearchHit.Folder(
                    folderId = folder.id,
                    browsable = true,
                    primary = folder.name,
                    meta = listOfNotNull("folder", formatFileCount(folder.fileCount).takeIf { folder.fileCount > 0 }).joinToString(" · "),
                    shareId = folder.shareId,
                    relPath = folder.relPath,
                    fileCount = folder.fileCount,
                    byteCount = folder.byteCount,
                    listed = folder.lastListedAtMs != null,
                )
            }
        }
        val moments = pointsOfInterest(matches, chip, f, kept).map { m ->
            SearchHit.Moment(
                fileId = m.fileId,
                startMs = m.startMs,
                primary = m.title,
                meta = (m.fileTitle ?: m.fileName.substringBeforeLast('.')) + " · " + formatClock(m.startMs),
                shareId = m.shareId,
                relPath = m.fileRelPath.substringBeforeLast('/', ""),
            )
        }
        SearchUiState(
            query = q,
            filter = f,
            facets = facetList,
            poi = chip.takeIf { spoofed == null },
            hits = hits,
            moreMatches = result.more,
            moments = moments,
            // Only where the names a query finds are shown: under All, with no chip.
            moreMoments = moreMoments && chip == null && f == SearchFilter.ALL,
            // Earlier searches are the owner's own words about the library:
            // spoof mode keeps them out of sight, and keeps no new ones.
            recent = if (spoofed == null) recent else emptyList(),
            scanningPath = runs.firstOrNull { it.status == ScanRunEntity.RUNNING }?.let { "/" + it.currentPath.ifEmpty { "…" }.let { p -> spoofed?.path(p) ?: p } },
            searched = searched,
            selection = sel,
            viewMode = mode,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SearchUiState())

    fun setQuery(text: String) {
        query.value = text
    }

    fun setFilter(f: SearchFilter) {
        filter.value = f
    }

    /** The moment sheet's answer; null is "Any moment", which clears the filter. */
    fun setPoi(title: String?) {
        poi.value = title
    }

    /** The grid/rows toggle on the results label: one choice for both groups, remembered across visits. */
    fun toggleViewMode() {
        viewModelScope.launch { prefs.setSearchViewMode(uiState.value.viewMode.toggled()) }
    }

    /** Called when the user commits a query (opens a hit or hits enter). */
    fun remember(text: String = query.value) {
        if (text.isBlank() || spoof.current != null) return
        viewModelScope.launch { library.rememberSearch(text) }
    }

    fun clearRecent() {
        viewModelScope.launch { library.clearRecentSearches() }
    }

    // ── Multi-selection ────────────────────────────────────────────────
    // The same store Browse and Library use, so a search result can be added
    // to a batch that started on a folder three screens ago.

    /** Long press: arm selection mode and pick what was held. */
    fun beginSelection(hit: SearchHit) {
        selection.begin()
        toggleSelection(hit)
    }

    fun toggleSelection(hit: SearchHit) {
        when (hit) {
            is SearchHit.Folder -> selection.toggleFolder(
                FolderPick(
                    folderId = hit.folderId, shareId = hit.shareId, relPath = hit.relPath,
                    fileCount = hit.fileCount, byteCount = hit.byteCount, listed = hit.listed,
                ),
            )
            is SearchHit.Title -> selection.toggleFile(
                FilePick(fileId = hit.fileId, shareId = hit.shareId, folderRelPath = hit.relPath, sizeBytes = hit.sizeBytes),
            )
            is SearchHit.File -> selection.toggleFile(
                FilePick(fileId = hit.fileId, shareId = hit.shareId, folderRelPath = hit.relPath, sizeBytes = hit.sizeBytes),
            )
            // A place, not a file: nothing to download.
            is SearchHit.Moment -> Unit
        }
    }

    /**
     * Every result on screen. One row per file since the two kinds stopped
     * being emitted together, but the `distinctBy` stays: the selection
     * store keys files by id anyway, so this costs nothing and keeps the
     * guarantee local to the code that relies on it.
     */
    fun selectAllHere() {
        val hits = uiState.value.hits
        selection.begin()
        selection.addAll(
            folders = hits.filterIsInstance<SearchHit.Folder>().map {
                FolderPick(it.folderId, it.shareId, it.relPath, it.fileCount, it.byteCount, it.listed)
            },
            files = hits.mapNotNull { hit ->
                when (hit) {
                    is SearchHit.Title -> FilePick(hit.fileId, hit.shareId, hit.relPath, hit.sizeBytes)
                    is SearchHit.File -> FilePick(hit.fileId, hit.shareId, hit.relPath, hit.sizeBytes)
                    is SearchHit.Folder -> null
                    is SearchHit.Moment -> null
                }
            }.distinctBy { it.fileId },
        )
    }

    fun cancelSelection() = selection.cancel()

    fun downloadSelection() {
        viewModelScope.launch { selection.download() }
    }

    private companion object {
        /**
         * How many files, and how many folders, a typed search lists. Typing
         * is meant to narrow, and the query runs again at every letter and
         * whenever a scan adds files. One more is asked for, so the count can
         * say "40+" instead of stopping there silently. A chip has no cap: it
         * is a filter, and every film carrying it is what was asked for.
         */
        const val LIMIT = 40

        /**
         * The names a typed query finds are one group above the files; more
         * than this and it becomes the list. A chip's moments have no cap:
         * they are every mark in the films listed.
         */
        const val MOMENTS_LIMIT = 20

        /**
         * How many named moments the filter sheet offers, commonest first.
         * Raised from 12 when single-film names joined the list — the sheet
         * scrolls, so the cap is only there to keep one pathological library
         * from building a list nobody can read.
         */
        const val FACETS_LIMIT = 50
    }
}
