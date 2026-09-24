package com.regolith.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.regolith.data.db.FolderEntity
import com.regolith.data.db.MediaFileEntity
import com.regolith.data.db.PlaybackProgressEntity
import com.regolith.data.db.ScanRunEntity
import com.regolith.data.prefs.AppPreferences
import com.regolith.data.repository.LibraryRepository
import com.regolith.data.repository.PhoneLibrary
import com.regolith.domain.media.DeviceSource
import com.regolith.domain.media.PhonePaths
import com.regolith.data.repository.SourceRepository
import com.regolith.data.scan.ScanRepository
import com.regolith.data.transfer.TransferRepository
import com.regolith.data.transfer.TransferRepository.Companion.causeEnum
import com.regolith.data.transfer.TransferRepository.Companion.statusEnum
import com.regolith.domain.transfer.TransferStatus
import com.regolith.ui.util.formatBytes
import com.regolith.ui.util.formatRemaining
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.regolith.domain.artwork.ArtworkKind
import com.regolith.domain.artwork.ArtworkOwner
import com.regolith.domain.artwork.ArtworkRequest
import com.regolith.domain.library.FolderKind
import com.regolith.domain.library.LibraryOrder
import com.regolith.domain.library.LibrarySort
import com.regolith.domain.library.SortDirection
import com.regolith.domain.library.ViewMode
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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.regolith.domain.transfer.FilePick
import com.regolith.domain.transfer.FolderPick
import com.regolith.ui.util.SelectionPresenter

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
    private val transfers: TransferRepository,
    private val selection: SelectionPresenter,
    private val phone: PhoneLibrary,
) : ViewModel() {

    @AssistedFactory
    interface Factory {
        fun create(folderId: Long?): LibraryViewModel
    }

    private var showAllFailed = false

    private val _uiState = MutableStateFlow(LibraryUiState())
    val uiState: StateFlow<LibraryUiState> = _uiState

    private var unsorted: List<LibraryTile> = emptyList()

    init {
        viewModelScope.launch { prefs.libraryOrder.collect { order -> _uiState.update { it.copy(order = order, tiles = sorted(unsorted, order)) } } }
        viewModelScope.launch { prefs.libraryViewMode.collect { mode -> _uiState.update { it.copy(viewMode = mode) } } }
        viewModelScope.launch { prefs.deviceViewMode.collect { mode -> _uiState.update { it.copy(device = it.device.copy(viewMode = mode)) } } }
        viewModelScope.launch { selection.observe().collect { sel -> _uiState.update { it.copy(selection = sel) } } }
        viewModelScope.launch {
            // The wall behind the "Network" tab, so an adopted copy shows on
            // the "On this device" tab only rather than on both.
            val shares = sources.observeNetworkShares()
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
                // Onto the live state, never the other way: see withWall.
                _uiState.update { it.withWall(state, sorted(state.tiles, it.order)) }
            }
        }
        viewModelScope.launch {
            val rows = transfers.observeAll()
            val files = rows.flatMapLatest { rs -> library.observeFilesByIds(rs.map { it.fileId }) }
            val progress = rows.flatMapLatest { rs -> library.observeProgress(rs.map { it.fileId }) }
            val servers = sources.observeServers()
            combine(rows, files, progress, servers) { rs, fs, ps, sv -> listOf(rs, fs, ps, sv) }.collect { values ->
                @Suppress("UNCHECKED_CAST") val rs = values[0] as List<com.regolith.data.db.TransferEntity>
                @Suppress("UNCHECKED_CAST") val fs = values[1] as List<MediaFileEntity>
                @Suppress("UNCHECKED_CAST") val ps = values[2] as List<PlaybackProgressEntity>
                val storage = withContext(Dispatchers.IO) { transfers.storage() }
                val origins = originsFor(fs)
                val built = buildDevice(rs, fs.associateBy { f -> f.id }, ps.associateBy { p -> p.fileId }, storage, origins)
                _uiState.update { it.copy(device = it.device.withRows(built)) }
            }
        }
        viewModelScope.launch {
            // Phone storage beside the downloads. Its own collector: MediaStore
            // and the transfer queue change on different clocks, and neither
            // should rebuild the other's half of the page.
            val files = phone.observeFiles()
            val progress = files.flatMapLatest { fs -> library.observeProgress(fs.map { it.id }) }
            combine(phone.access, phone.observeFolders(), files, progress) { access, folders, fs, ps ->
                val progressById = ps.associateBy { it.fileId }
                val byFolder = fs.groupBy { it.folderId }
                // Newest video first inside a folder, and the folder with the
                // newest video first on the page: the clip you just shot is
                // the one you came here for.
                val built = folders.mapNotNull { folder ->
                    val videos = byFolder[folder.id].orEmpty().sortedByDescending { it.modifiedAtMs }
                    if (videos.isEmpty()) return@mapNotNull null
                    videos.first().modifiedAtMs to PhoneFolder(
                        folderId = folder.id,
                        relPath = folder.relPath,
                        name = folder.name,
                        path = PhonePaths.display(folder.relPath),
                        videos = videos.map { phoneRow(it, progressById[it.id]) },
                    )
                }.sortedByDescending { it.first }.map { it.second }
                access to built
            }.collect { (access, folders) ->
                _uiState.update { s ->
                    // A folder chip whose folder has just emptied (hidden, or
                    // its last video deleted) falls back to everything rather
                    // than showing a blank page with no chip lit.
                    val filter = s.device.filter.let { f -> if (f is DeviceFilter.Folder && folders.none { it.folderId == f.folderId }) DeviceFilter.All else f }
                    s.copy(device = s.device.copy(phoneAccess = access, phoneFolders = folders, filter = filter))
                }
            }
        }
    }

    /**
     * Which server each copy came from, by file id: "TOWER". Empty for a copy
     * already adopted into "This device" — its server is gone, and naming
     * the synthetic one would say nothing.
     */
    private suspend fun originsFor(files: List<MediaFileEntity>): Map<Long, String> {
        val byShare = files.map { it.shareId }.distinct().associateWith { id -> library.shareLabel(id) }
        return files.associate { f ->
            val label = byShare[f.shareId].orEmpty()
            f.id to if (label.endsWith(" · ${DeviceSource.SHARE}") || label.isEmpty()) "" else label.substringBefore(" · ")
        }
    }

    /** A phone video as a finished row: "0:48 · 96 MB", or "1h 30m · 2.2 GB · 15m left". */
    private fun phoneRow(file: MediaFileEntity, progress: PlaybackProgressEntity?): DeviceRow {
        val parsed = ParsedName(file.titleParsed ?: file.name.substringBeforeLast('.'), file.year, file.season, file.episode)
        val meta = listOfNotNull(
            VideoInfo.resolutionLabelFor(file.width, file.height).ifEmpty { null },
            file.durationMs?.takeIf { it > 0 }?.let { formatDurationShort(it) },
            formatBytes(file.sizeBytes),
            if (progress != null && progress.positionMs > 0 && progress.durationMs > 0 && !progress.completed) formatRemaining(progress.positionMs, progress.durationMs) else null,
        ).joinToString(" · ")
        return DeviceRow(
            fileId = file.id,
            name = if (parsed.matched) parsed.display else file.name.substringBeforeLast('.'),
            status = TransferStatus.DONE, cause = null, causeBytes = null,
            bytesDone = file.sizeBytes, totalBytes = file.sizeBytes, meta = meta,
            phone = true,
        )
    }

    /** The device tab (design section 05, "On device · transfers"): files that play first, then the failures. */
    private fun buildDevice(
        rows: List<com.regolith.data.db.TransferEntity>,
        files: Map<Long, MediaFileEntity>,
        progress: Map<Long, PlaybackProgressEntity>,
        storage: TransferRepository.Storage,
        origins: Map<Long, String> = emptyMap(),
    ): DeviceUiState {
        fun row(t: com.regolith.data.db.TransferEntity): DeviceRow? {
            val file = files[t.fileId] ?: return null
            val parsed = ParsedName(file.titleParsed ?: file.name.substringBeforeLast('.'), file.year, file.season, file.episode)
            val p = progress[t.fileId]
            val meta = listOfNotNull(
                // Where it came from, first: on a page that also lists the
                // phone's own videos, "a copy of something on TOWER" is the
                // fact that tells the two apart.
                origins[t.fileId]?.ifEmpty { null },
                VideoInfo.resolutionLabelFor(file.width, file.height).ifEmpty { null },
                formatBytes(t.totalBytes),
                if (p != null && p.positionMs > 0 && p.durationMs > 0 && !p.completed) formatRemaining(p.positionMs, p.durationMs) else null,
            ).joinToString(" · ")
            return DeviceRow(
                fileId = t.fileId,
                name = if (parsed.matched) parsed.display else file.name.substringBeforeLast('.'),
                status = t.statusEnum(), cause = t.causeEnum(), causeBytes = t.causeBytes,
                bytesDone = t.bytesDone, totalBytes = t.totalBytes, meta = meta,
            )
        }
        val all = rows.mapNotNull(::row)
        return DeviceUiState(
            usedBytes = storage.usedBytes,
            totalBytes = storage.totalBytes,
            ready = all.filter { it.status == TransferStatus.DONE },
            inFlight = all.filter { it.status == TransferStatus.QUEUED || it.status == TransferStatus.RUNNING || it.status == TransferStatus.PAUSED },
            failed = all.filter { it.status == TransferStatus.FAILED },
            showAllFailed = showAllFailed,
        )
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
                        resolutionLabel = VideoInfo.resolutionLabelFor(beneath.mapNotNull { it.width }.maxOrNull(), beneath.mapNotNull { it.height }.maxOrNull()),
                        artwork = ArtworkRequest(ArtworkOwner.Folder(folder.id), ArtworkKind.POSTER),
                        addedAtMs = beneath.maxOfOrNull { it.addedAtMs } ?: 0,
                        sizeBytes = beneath.sumOf { it.sizeBytes },
                        durationMs = beneath.mapNotNull { it.durationMs }.takeIf { it.isNotEmpty() }?.sum(),
                        height = beneath.mapNotNull { it.height }.maxOrNull(),
                        shareId = folder.shareId,
                        relPath = folder.relPath,
                        directFileCount = folder.fileCount,
                        directByteCount = folder.byteCount,
                        listed = folder.lastListedAtMs != null,
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
            unreachable = servers.filter { s -> s.unreachableSinceMs != null && shares.any { it.serverId == s.id } }
                .map { UnreachableServer(it.id, it.name, it.lastSeenAtMs) },
        )
    }

    // ── Multi-selection ────────────────────────────────────────────────
    // Same store as Browse and Search, so a wall and a folder tree can
    // contribute to one batch. See SelectionStore for why it is not here.

    /** Long press: arm selection mode and pick what was held. */
    fun beginSelection(tile: LibraryTile) {
        selection.begin()
        toggleSelection(tile)
    }

    fun toggleSelection(tile: LibraryTile) {
        when (tile) {
            is LibraryTile.Collection -> selection.toggleFolder(tile.toPick())
            is LibraryTile.Title -> selection.toggleFile(tile.toPick())
        }
    }

    /** Everything on this wall, leaving picks made on other screens alone. */
    fun selectAllHere() {
        val tiles = _uiState.value.tiles
        selection.begin()
        selection.addAll(
            folders = tiles.filterIsInstance<LibraryTile.Collection>().map { it.toPick() },
            files = tiles.filterIsInstance<LibraryTile.Title>().map { it.toPick() },
        )
    }

    fun cancelSelection() = selection.cancel()

    fun downloadSelection() {
        viewModelScope.launch { selection.download() }
    }

    /**
     * A collection's pick carries its DIRECT counts, not the subtree's.
     * `fileCount` on the tile is everything beneath it, which is what the
     * wall shows; the tally sums every folder in the covered subtree
     * separately, so handing it the subtree total here would count twice.
     */
    private fun LibraryTile.Collection.toPick() = FolderPick(
        folderId = folderId, shareId = shareId, relPath = relPath,
        fileCount = directFileCount, byteCount = directByteCount, listed = listed,
    )

    private fun LibraryTile.Title.toPick() =
        FilePick(fileId = fileId, shareId = shareId, folderRelPath = folderRelPath, sizeBytes = sizeBytes)

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
            fileName = file.name,
            progress = progress?.takeIf { it.positionMs > 0 && it.durationMs > 0 && !it.completed }?.let { it.positionMs.toFloat() / it.durationMs },
            meta = duration?.let { formatDurationShort(it) } ?: formatBytes(file.sizeBytes),
            artwork = ArtworkRequest(ArtworkOwner.File(file.id), ArtworkKind.POSTER),
            addedAtMs = file.addedAtMs,
            sizeBytes = file.sizeBytes,
            durationMs = duration,
            height = file.height,
            shareId = file.shareId,
            folderRelPath = file.relPath.substringBeforeLast('/', ""),
        )
    }

    /**
     * The wall in [order]. A tile with no runtime or resolution yet (not
     * probed) goes LAST in both directions: reversing the sort should not
     * bring every unknown to the top. Ties fall back to the name, so equal
     * sizes do not shuffle between recompositions.
     */
    private fun sorted(tiles: List<LibraryTile>, order: LibraryOrder): List<LibraryTile> {
        val byName = compareBy<LibraryTile, String>(String.CASE_INSENSITIVE_ORDER) { it.name }
        val key: (LibraryTile) -> Long? = when (order.sort) {
            LibrarySort.NAME -> { _ -> 0L }
            LibrarySort.DATE_ADDED -> { t -> t.addedAtMs }
            LibrarySort.FILE_SIZE -> { t -> t.sizeBytes }
            LibrarySort.RUNTIME -> { t -> t.durationMs }
            LibrarySort.RESOLUTION -> { t -> t.height?.toLong() }
        }
        val primary = if (order.sort == LibrarySort.NAME) byName else compareBy<LibraryTile> { key(it) }.then(byName)
        val directed = if (order.direction == SortDirection.ASCENDING) primary else primary.reversed()
        return tiles.sortedWith(compareBy<LibraryTile> { key(it) == null }.then(directed))
    }

    fun openSortSheet(open: Boolean) = _uiState.update { it.copy(sortSheetOpen = open) }

    /**
     * A row in the sort sheet was tapped. A new criterion closes the sheet,
     * as it always did; the one already in use reverses and leaves the
     * sheet open, so you can see which way it now runs.
     */
    fun pickSort(sort: LibrarySort) {
        val current = _uiState.value.order
        val next = current.pick(sort)
        _uiState.update { it.copy(order = next, sortSheetOpen = sort == current.sort, tiles = sorted(unsorted, next)) }
        viewModelScope.launch { prefs.setLibraryOrder(next) }
    }

    /** Poster wall <-> rows. Written to preferences; the collector above puts it back on the state. */
    /** The On this device tab's own tiles-or-rows switch. */
    fun toggleDeviceViewMode() {
        viewModelScope.launch { prefs.setDeviceViewMode(_uiState.value.device.viewMode.toggled()) }
    }

    fun toggleViewMode() {
        viewModelScope.launch { prefs.setLibraryViewMode(_uiState.value.viewMode.toggled()) }
    }

    /** "Scan first" nudge and pull-to-refresh both land here. */
    fun scanAll() {
        viewModelScope.launch { scans.scanAll() }
    }

    /** "Try again" on the out-of-reach card: one cheap listing per server. */
    fun tryAgain() {
        viewModelScope.launch {
            _uiState.update { it.copy(checkingReachability = true) }
            _uiState.value.unreachable.forEach { sources.probeReachable(it.serverId) }
            _uiState.update { it.copy(checkingReachability = false) }
        }
    }

    // --- device tab: phone storage

    /** The chips. */
    fun setDeviceFilter(filter: DeviceFilter) = updateDevice { d -> d.copy(filter = filter, picked = null) }

    /**
     * The device tab came into view. The permission may have changed in
     * Android's settings, and videos may have arrived while the app was
     * away, so re-read both. Cheap: a sync writes only what changed.
     */
    fun onDeviceShown() {
        phone.refreshAccess()
        phone.requestSync()
    }

    /** The system permission dialog answered. */
    fun onPhoneAccessAnswered() = onDeviceShown()

    // --- device tab
    fun retryTransfer(fileId: Long) = viewModelScope.launch { transfers.start(fileId) }.let { }
    fun cancelTransfer(fileId: Long) = viewModelScope.launch { transfers.cancel(fileId) }.let { }
    fun removeCopy(fileId: Long) = viewModelScope.launch { transfers.remove(fileId) }.let { }
    fun clearFailed() = viewModelScope.launch { transfers.clearFailed() }.let { }
    fun toggleShowAllFailed() {
        showAllFailed = !showAllFailed
        _uiState.update { it.copy(device = it.device.copy(showAllFailed = showAllFailed)) }
    }

    // --- device tab: picking copies to remove
    //
    // Held on the UiState rather than in SelectionStore. The download
    // selection is app-scoped because a pick three folders deep has to
    // survive walking the tree; this one cannot leave the tab it is on, and
    // one store for both would let a batch mean "download these" and
    // "delete these" in the same breath.

    private fun updateDevice(block: (DeviceUiState) -> DeviceUiState) =
        _uiState.update { it.copy(device = block(it.device)) }

    /** Long press on a copy: start picking, and pick the one held. */
    fun beginDeviceSelection(fileId: Long) = updateDevice { d ->
        d.copy(picked = (d.picked ?: emptySet()) + fileId)
    }

    fun toggleDeviceSelection(fileId: Long) = updateDevice { d ->
        val current = d.picked ?: emptySet()
        d.copy(picked = if (fileId in current) current - fileId else current + fileId)
    }

    fun selectAllOnDevice() = updateDevice { d -> d.copy(picked = d.allFileIds.toSet()) }

    fun cancelDeviceSelection() = updateDevice { d -> d.copy(picked = null) }

    /** Ask before removing: the files go, and getting them back is another download. */
    fun askRemovePicked() = updateDevice { d ->
        if (d.picked.isNullOrEmpty()) d else d.copy(confirmRemove = RemoveTarget.PICKED)
    }

    /** "Clear all": ask about everything the page lists. */
    fun askRemoveAll() = updateDevice { d ->
        if (d.allFileIds.isEmpty()) d else d.copy(confirmRemove = RemoveTarget.EVERYTHING)
    }

    fun dismissRemoveConfirm() = updateDevice { d -> d.copy(confirmRemove = null) }

    /** Confirmed. Acts on what the dialog said it was about, not on what is picked now. */
    fun confirmRemove() {
        val device = _uiState.value.device
        val target = device.confirmRemove ?: return
        val picked = device.picked.orEmpty()
        viewModelScope.launch {
            when (target) {
                RemoveTarget.EVERYTHING -> transfers.removeEverything()
                // Guarded again here: the dialog cannot open on an empty
                // selection, and if it somehow did this does nothing rather
                // than falling through to "everything".
                RemoveTarget.PICKED -> if (picked.isNotEmpty()) transfers.removeAll(picked)
            }
            updateDevice { d -> d.copy(picked = null, confirmRemove = null) }
        }
    }
}
