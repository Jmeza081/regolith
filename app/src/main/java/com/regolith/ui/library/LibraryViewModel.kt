package com.regolith.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.regolith.data.db.FolderEntity
import com.regolith.data.db.MediaFileEntity
import com.regolith.data.db.PlaybackProgressEntity
import com.regolith.data.db.ScanRunEntity
import com.regolith.data.prefs.AppPreferences
import com.regolith.data.repository.LibraryRepository
import com.regolith.data.repository.SourceRepository
import com.regolith.data.scan.ScanRepository
import com.regolith.domain.artwork.ArtworkKind
import com.regolith.domain.artwork.ArtworkOwner
import com.regolith.domain.artwork.ArtworkRequest
import com.regolith.domain.library.FolderKind
import com.regolith.domain.library.LibrarySort
import com.regolith.domain.library.ParsedName
import com.regolith.domain.playback.VideoInfo
import com.regolith.ui.util.formatDurationShort
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The poster wall (design section 05). Reads only from Room; the scan
 * fills Room. `folderId == null` is the wall of every enabled share's root:
 * collections and loose titles. Inside a collection the wall is that
 * folder's children.
 *
 * What becomes a tile:
 *  - a TITLE folder    -> one Title tile for its largest video (its poster.jpg applies to that file)
 *  - a loose video     -> a Title tile (matched if the name carried a year or episode)
 *  - any other folder  -> a Collection tile, counting the files beneath it
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel(assistedFactory = LibraryViewModel.Factory::class)
class LibraryViewModel @AssistedInject constructor(
    @Assisted private val folderId: Long?,
    private val library: LibraryRepository,
    private val sources: SourceRepository,
    private val scans: ScanRepository,
    private val prefs: AppPreferences,
) : ViewModel() {

    @AssistedFactory
    interface Factory {
        fun create(folderId: Long?): LibraryViewModel
    }

    private val _uiState = MutableStateFlow(LibraryUiState())
    val uiState: StateFlow<LibraryUiState> = _uiState

    private var unsorted: List<LibraryTile> = emptyList()

    init {
        viewModelScope.launch { prefs.librarySort.collect { sort -> _uiState.update { it.copy(sort = sort, tiles = sorted(unsorted, sort)) } } }
        viewModelScope.launch {
            val shares = sources.observeEnabledShares()
            val servers = sources.observeServers()
            val parents: kotlinx.coroutines.flow.Flow<List<FolderEntity>> = if (folderId == null) {
                shares.flatMapLatest { list -> if (list.isEmpty()) flowOf(emptyList()) else library.observeRoots(list.map { it.id }) }
            } else {
                library.observeFolder(folderId).flatMapLatest { f -> flowOf(listOfNotNull(f)) }
            }
            val children = parents.flatMapLatest { ps -> library.observeFoldersInShares(ps.map { it.shareId }.distinct()) }
            val files = parents.flatMapLatest { ps -> library.observeFilesInShares(ps.map { it.shareId }.distinct()) }
            val progress = files.flatMapLatest { fs -> library.observeProgress(fs.map { it.id }) }
            val scanState = shares.flatMapLatest { list -> if (list.isEmpty()) flowOf(emptyList()) else scans.observeLatest(list.map { it.id }) }

            combine(shares, servers, parents, children, files, progress, scanState) { values ->
                @Suppress("UNCHECKED_CAST")
                val shareList = values[0] as List<com.regolith.domain.model.Share>
                @Suppress("UNCHECKED_CAST")
                val serverList = values[1] as List<com.regolith.domain.model.Server>
                @Suppress("UNCHECKED_CAST")
                val parentList = values[2] as List<FolderEntity>
                @Suppress("UNCHECKED_CAST")
                val childList = values[3] as List<FolderEntity>
                @Suppress("UNCHECKED_CAST")
                val fileList = values[4] as List<MediaFileEntity>
                @Suppress("UNCHECKED_CAST")
                val progressList = values[5] as List<PlaybackProgressEntity>
                @Suppress("UNCHECKED_CAST")
                val runs = values[6] as List<ScanRunEntity>
                build(shareList, serverList, parentList, childList, fileList, progressList, runs)
            }.collect { state ->
                unsorted = state.tiles
                _uiState.update { state.copy(sort = it.sort, sortSheetOpen = it.sortSheetOpen, tiles = sorted(state.tiles, it.sort)) }
            }
        }
    }

    private fun build(
        shares: List<com.regolith.domain.model.Share>,
        servers: List<com.regolith.domain.model.Server>,
        parents: List<FolderEntity>,
        children: List<FolderEntity>,
        allFiles: List<MediaFileEntity>,
        progress: List<PlaybackProgressEntity>,
        runs: List<ScanRunEntity>,
    ): LibraryUiState {
        val byFolder = allFiles.groupBy { it.folderId }
        val byParent = children.filter { it.parentId != null }.groupBy { it.parentId }
        val progressById = progress.associateBy { it.fileId }
        val tiles = mutableListOf<LibraryTile>()

        // Files beneath a folder, walking the in-memory tree (no extra queries).
        fun filesUnder(folder: FolderEntity): List<MediaFileEntity> {
            val out = mutableListOf<MediaFileEntity>()
            val queue = ArrayDeque<Long>().apply { add(folder.id) }
            while (queue.isNotEmpty()) {
                val id = queue.removeFirst()
                out += byFolder[id].orEmpty()
                queue.addAll(byParent[id].orEmpty().map { it.id })
            }
            return out
        }

        for (parent in parents) {
            for (folder in byParent[parent.id].orEmpty()) {
                val kind = folder.kind?.let { runCatching { FolderKind.valueOf(it) }.getOrNull() }
                val direct = byFolder[folder.id].orEmpty()
                if (kind == FolderKind.TITLE && direct.isNotEmpty()) {
                    val main = direct.maxBy { it.sizeBytes }
                    tiles += titleTile(main, progressById[main.id], ParsedName(folder.titleParsed ?: folder.name, folder.year))
                } else {
                    val beneath = filesUnder(folder)
                    if (beneath.isEmpty() && kind != FolderKind.COLLECTION && kind != FolderKind.SHOW) continue
                    tiles += LibraryTile.Collection(
                        folderId = folder.id,
                        name = folder.name,
                        fileCount = beneath.size,
                        artwork = ArtworkRequest(ArtworkOwner.Folder(folder.id), ArtworkKind.POSTER),
                        addedAtMs = beneath.maxOfOrNull { it.addedAtMs } ?: 0,
                        sizeBytes = beneath.sumOf { it.sizeBytes },
                        durationMs = beneath.mapNotNull { it.durationMs }.takeIf { it.isNotEmpty() }?.sum(),
                        height = beneath.mapNotNull { it.height }.maxOrNull(),
                    )
                }
            }
            for (file in byFolder[parent.id].orEmpty()) {
                tiles += titleTile(file, progressById[file.id], null)
            }
        }

        val shareIds = shares.map { it.id }.toSet()
        val fileCount = allFiles.count { it.shareId in shareIds }
        val serverNames = servers.filter { s -> shares.any { it.serverId == s.id } }.map { it.name }
        val parentTitle = if (folderId != null) parents.firstOrNull()?.name ?: "" else "Library"
        val meta = if (folderId != null) {
            "${tiles.size} titles"
        } else {
            (serverNames + shares.map { it.name }.distinct()).joinToString(" · ") + " · " + "%,d".format(fileCount) + " files"
        }
        return LibraryUiState(
            title = parentTitle,
            meta = meta.takeIf { shares.isNotEmpty() },
            tiles = tiles,
            loaded = true,
            noSource = shares.isEmpty(),
            scanning = runs.any { it.status == ScanRunEntity.RUNNING },
            scannedOnce = shares.any { it.lastScanAtMs != null } || runs.any { it.status == ScanRunEntity.DONE },
        )
    }

    private fun titleTile(file: MediaFileEntity, progress: PlaybackProgressEntity?, folderName: ParsedName?): LibraryTile.Title {
        val parsed = folderName ?: ParsedName(file.titleParsed ?: file.name.substringBeforeLast('.'), file.year, file.season, file.episode)
        val matched = parsed.matched
        val duration = progress?.durationMs?.takeIf { it > 0 } ?: file.durationMs
        return LibraryTile.Title(
            fileId = file.id,
            name = if (matched) parsed.display else file.name.substringBeforeLast('.'),
            resolutionLabel = VideoInfo.resolutionLabelFor(file.width, file.height),
            matched = matched,
            unwatched = progress == null || (progress.positionMs == 0L && !progress.completed),
            meta = listOfNotNull(
                if (!matched) ".${file.ext.uppercase()}" else null,
                duration?.let { formatDurationShort(it) },
                if (!matched) "No match" else null,
            ).joinToString(" · "),
            artwork = ArtworkRequest(ArtworkOwner.File(file.id), ArtworkKind.POSTER),
            addedAtMs = file.addedAtMs,
            sizeBytes = file.sizeBytes,
            durationMs = duration,
            height = file.height,
        )
    }

    private fun sorted(tiles: List<LibraryTile>, sort: LibrarySort): List<LibraryTile> = when (sort) {
        LibrarySort.NAME -> tiles.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
        LibrarySort.DATE_ADDED -> tiles.sortedByDescending { it.addedAtMs }
        LibrarySort.FILE_SIZE -> tiles.sortedByDescending { it.sizeBytes }
        LibrarySort.RUNTIME -> tiles.sortedByDescending { it.durationMs ?: -1 }
        LibrarySort.RESOLUTION -> tiles.sortedByDescending { it.height ?: -1 }
    }

    fun openSortSheet(open: Boolean) = _uiState.update { it.copy(sortSheetOpen = open) }

    fun setSort(sort: LibrarySort) {
        _uiState.update { it.copy(sort = sort, sortSheetOpen = false, tiles = sorted(unsorted, sort)) }
        viewModelScope.launch { prefs.setLibrarySort(sort) }
    }

    /** "Scan first" nudge and pull-to-refresh both land here. */
    fun scanAll() {
        viewModelScope.launch { scans.scanAll() }
    }
}
