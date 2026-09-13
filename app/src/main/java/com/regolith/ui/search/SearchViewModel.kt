package com.regolith.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.regolith.data.db.FolderEntity
import com.regolith.data.db.MediaFileEntity
import com.regolith.data.db.ScanRunEntity
import com.regolith.data.repository.LibraryRepository
import com.regolith.data.repository.SourceRepository
import com.regolith.data.repository.UserChapterRepository
import com.regolith.data.scan.ScanRepository
import com.regolith.domain.library.FolderKind
import com.regolith.domain.library.ParsedName
import com.regolith.domain.playback.ChapterFacet
import com.regolith.domain.playback.ChapterMatch
import com.regolith.domain.playback.VideoInfo
import com.regolith.ui.util.formatBytes
import com.regolith.ui.util.formatClock
import com.regolith.ui.util.formatDurationShort
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
import javax.inject.Inject
import com.regolith.ui.util.formatFileCount
import com.regolith.domain.transfer.FilePick
import com.regolith.domain.transfer.FolderPick
import com.regolith.ui.util.SelectionPresenter
import com.regolith.ui.util.SelectionUiState

enum class SearchFilter(val label: String) { ALL("All"), UNWATCHED("Unwatched"), UHD("4K"), ON_DEVICE("On device") }

/** One row of results. Titles, raw filenames and folders share the list. */
sealed interface SearchHit {
    val testTag: String
    val primary: String
    val meta: String

    /** What a download pick needs: which share, and where in it. */
    val shareId: Long
    val relPath: String

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
    /** Points of interest offered as chips: names carried by more than one film. */
    val facets: List<ChapterFacet> = emptyList(),
    /** The chip currently on, if any. Narrows everything to films carrying it. */
    val poi: String? = null,
    val hits: List<SearchHit> = emptyList(),
    /** Points of interest: chapters whose names match, shown above [hits]. */
    val moments: List<SearchHit.Moment> = emptyList(),
    val recent: List<String> = emptyList(),
    /** "Still reading /media/archive — matches will keep arriving." */
    val scanningPath: String? = null,
    /** The user has typed something and results have been computed for it. */
    val searched: Boolean = false,
    /** Non-null while a multi-selection is running. */
    val selection: SelectionUiState? = null,
)

/**
 * Search (design section 06): filenames, parsed titles and folders in one
 * list, matched as you type. Everything comes from Room's full-text
 * indexes, so results keep arriving while a scan is still walking.
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val library: LibraryRepository,
    sources: SourceRepository,
    scans: ScanRepository,
    private val selection: SelectionPresenter,
    userChapters: UserChapterRepository,
) : ViewModel() {

    private val query = MutableStateFlow("")
    private val filter = MutableStateFlow(SearchFilter.ALL)
    private val poi = MutableStateFlow<String?>(null)

    // The chips themselves: free of the share, since the sidecar import put
    // every chapter name in the table when the folder was listed.
    private val facets = userChapters.facets(FACETS_LIMIT)

    /**
     * A point of interest narrows to the films carrying it, which is why it
     * stands in for the query when there is none: a chip on its own is a
     * search. Folders drop out while one is on — a folder has no chapters,
     * so it can never carry the thing being asked for.
     */
    private val results = combine(query.debounce(150), poi) { q, p -> q to p }.flatMapLatest { (q, p) ->
        when {
            p != null && q.isBlank() ->
                userChapters.filesWith(p, LIMIT).map { Triple(it, emptyList<FolderEntity>(), true) }
            p != null ->
                combine(library.searchFiles(q, LIMIT), userChapters.filesWith(p, LIMIT)) { files, carrying ->
                    val ids = carrying.mapTo(HashSet()) { it.id }
                    Triple(files.filter { it.id in ids }, emptyList<FolderEntity>(), true)
                }
            q.isBlank() -> flowOf(Triple(emptyList<MediaFileEntity>(), emptyList<FolderEntity>(), false))
            else -> combine(library.searchFiles(q, LIMIT), library.searchFolders(q, LIMIT)) { files, folders -> Triple(files, folders, true) }
        }
    }
    private val progress = results.flatMapLatest { (files, _, _) -> library.observeProgress(files.map { it.id }) }
    // Chapter names: every occurrence of the chip's name when one is on,
    // otherwise whatever the query matches, through the same MATCH rule.
    private val moments = combine(query.debounce(150), poi) { q, p -> q to p }.flatMapLatest { (q, p) ->
        when {
            p != null -> userChapters.occurrences(p, MOMENTS_LIMIT)
            q.isBlank() -> flowOf(emptyList())
            else -> userChapters.search(q, MOMENTS_LIMIT)
        }
    }
    private val running = sources.observeEnabledShares().flatMapLatest { list ->
        if (list.isEmpty()) flowOf(emptyList()) else scans.observeLatest(list.map { it.id })
    }

    val uiState: StateFlow<SearchUiState> = combine(
        query, filter, results, progress, running, library.observeRecentSearches(8), selection.observe(), moments, facets, poi,
    ) { values ->
        val q = values[0] as String
        val f = values[1] as SearchFilter
        @Suppress("UNCHECKED_CAST")
        val result = values[2] as Triple<List<MediaFileEntity>, List<FolderEntity>, Boolean>
        val files = result.first
        val folders = result.second
        val searched = result.third
        @Suppress("UNCHECKED_CAST")
        val progressById = (values[3] as List<com.regolith.data.db.PlaybackProgressEntity>).associateBy { it.fileId }
        @Suppress("UNCHECKED_CAST")
        val runs = values[4] as List<ScanRunEntity>
        @Suppress("UNCHECKED_CAST")
        val recent = (values[5] as List<com.regolith.data.db.RecentSearchEntity>).map { it.query }
        val sel = values[6] as SelectionUiState?
        @Suppress("UNCHECKED_CAST")
        val matches = values[7] as List<ChapterMatch>
        @Suppress("UNCHECKED_CAST")
        val facetList = values[8] as List<ChapterFacet>
        val chip = values[9] as String?

        val hits = mutableListOf<SearchHit>()
        for (file in files) {
            val p = progressById[file.id]
            val unwatched = p == null || (p.positionMs == 0L && !p.completed)
            when (f) {
                SearchFilter.UNWATCHED -> if (!unwatched) continue
                SearchFilter.UHD -> if (VideoInfo.resolutionLabelFor(file.width, file.height) != "4K") continue
                SearchFilter.ON_DEVICE -> continue // downloads arrive in Phase 5
                SearchFilter.ALL -> Unit
            }
            val parsed = ParsedName(file.titleParsed ?: file.name.substringBeforeLast('.'), file.year, file.season, file.episode)
            val res = VideoInfo.resolutionLabelFor(file.width, file.height).ifEmpty { null }
            val folderName = file.relPath.substringBeforeLast('/', "").substringAfterLast('/')
            if (parsed.matched) {
                hits += SearchHit.Title(
                    fileId = file.id,
                    primary = parsed.display,
                    meta = listOfNotNull(res, file.durationMs?.let { formatDurationShort(it) }, folderName.ifEmpty { null }).joinToString(" · "),
                    shareId = file.shareId,
                    relPath = file.relPath.substringBeforeLast('/', ""),
                    sizeBytes = file.sizeBytes,
                )
            }
            hits += SearchHit.File(
                fileId = file.id,
                primary = file.name,
                meta = listOfNotNull(res, formatBytes(file.sizeBytes), "/" + file.relPath.substringBeforeLast('/', "")).joinToString(" · "),
                shareId = file.shareId,
                relPath = file.relPath.substringBeforeLast('/', ""),
                sizeBytes = file.sizeBytes,
            )
        }
        if (f == SearchFilter.ALL) {
            for (folder in folders) {
                val kind = folder.kind?.let { runCatching { FolderKind.valueOf(it) }.getOrNull() }
                hits += SearchHit.Folder(
                    folderId = folder.id,
                    browsable = true,
                    primary = folder.name,
                    meta = listOfNotNull(if (kind == FolderKind.TITLE) "title" else "folder", formatFileCount(folder.fileCount).takeIf { folder.fileCount > 0 }).joinToString(" · "),
                    shareId = folder.shareId,
                    relPath = folder.relPath,
                    fileCount = folder.fileCount,
                    byteCount = folder.byteCount,
                    listed = folder.lastListedAtMs != null,
                )
            }
        }
        // Without a chip, points of interest only under "All": the other
        // filters are about the file (watched, 4K, on device), and a moment
        // is not a file. With a chip they ARE the result, so they stay —
        // narrowed to the films that survived the filter above.
        val visible = files.mapTo(HashSet()) { it.id }
        val shown = if (chip != null) matches.filter { it.fileId in visible } else matches
        val moments = if (chip != null || f == SearchFilter.ALL) shown.map { m ->
            SearchHit.Moment(
                fileId = m.fileId,
                startMs = m.startMs,
                primary = m.title,
                meta = (m.fileTitle ?: m.fileName.substringBeforeLast('.')) + " · " + formatClock(m.startMs),
                shareId = m.shareId,
                relPath = m.fileRelPath.substringBeforeLast('/', ""),
            )
        } else emptyList()
        SearchUiState(
            query = q,
            filter = f,
            facets = facetList,
            poi = chip,
            hits = hits,
            moments = moments,
            recent = recent,
            scanningPath = runs.firstOrNull { it.status == ScanRunEntity.RUNNING }?.let { "/" + it.currentPath.ifEmpty { "…" } },
            searched = searched,
            selection = sel,
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

    /** Called when the user commits a query (opens a hit or hits enter). */
    fun remember(text: String = query.value) {
        if (text.isBlank()) return
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
     * Every result on screen. Title and File hits can name the SAME file —
     * a matched video appears as both — and the store keys files by id, so
     * the duplicate collapses rather than being picked twice.
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
        const val LIMIT = 40

        /** Points of interest are one group above the files; more than this and it becomes the list. */
        const val MOMENTS_LIMIT = 20

        /** Chips for the commonest recurring names; past this the row is a wall, not a filter. */
        const val FACETS_LIMIT = 12
    }
}
