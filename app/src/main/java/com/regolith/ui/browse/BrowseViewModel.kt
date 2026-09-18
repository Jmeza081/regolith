package com.regolith.ui.browse

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.regolith.data.fileops.FileOpsRepository
import com.regolith.data.prefs.AppPreferences
import com.regolith.data.repository.LibraryRepository
import com.regolith.data.repository.SourceRepository
import com.regolith.domain.media.MediaFileTypes
import com.regolith.domain.model.BrowseItem
import com.regolith.domain.transfer.FilePick
import com.regolith.domain.transfer.FolderPick
import com.regolith.domain.playback.VideoInfo
import com.regolith.domain.fileops.FileOpResult
import com.regolith.domain.fileops.FileOpTarget
import com.regolith.domain.smb.SmbFailure
import com.regolith.ui.components.MoveChild
import com.regolith.ui.components.MoveSheetState
import com.regolith.ui.util.FileOpMessages
import com.regolith.ui.util.SelectionPresenter
import com.regolith.ui.util.SelectionUiState
import com.regolith.ui.util.formatBytes
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Browse (design section 07): the share as it actually is. Reads only from
 * Room; entering a folder triggers one listing of it over SMB, and if the
 * share is out of reach the saved listing is shown with a banner instead.
 *
 * `folderId == null` is the root: the enabled shares across all servers.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel(assistedFactory = BrowseViewModel.Factory::class)
class BrowseViewModel @AssistedInject constructor(
    @Assisted private val folderId: Long?,
    private val library: LibraryRepository,
    private val sources: SourceRepository,
    private val prefs: AppPreferences,
    private val selection: SelectionPresenter,
    private val fileOps: FileOpsRepository,
) : ViewModel() {

    @AssistedFactory
    interface Factory {
        fun create(folderId: Long?): BrowseViewModel
    }

    private val _uiState = MutableStateFlow(BrowseUiState())
    val uiState: StateFlow<BrowseUiState> = _uiState

    init {
        if (folderId == null) observeRoot() else observeFolder(folderId)
        viewModelScope.launch { prefs.browseViewMode.collect { mode -> _uiState.update { it.copy(viewMode = mode) } } }
        observeTree()
        viewModelScope.launch {
            selection.observe().collect { sel ->
                _uiState.update {
                    it.copy(
                        selection = sel,
                        canManage = sel != null && sel.itemCount > 0,
                        canRename = sel != null && sel.itemCount == 1,
                        selectionHint = hintFor(sel),
                    )
                }
            }
        }
        _uiState.update { it.copy(currentFolderId = folderId) }
    }

    /**
     * The share tree for a wide window: every enabled share and the folders
     * directly under it. Built from the folder rows the Library already keeps
     * in memory for the whole share, so it costs one more query, not one per
     * node, and it stays live as a scan discovers folders.
     */
    private fun observeTree() {
        viewModelScope.launch {
            sources.observeEnabledShares()
                .flatMapLatest { shares ->
                    val ids = shares.map { it.id }
                    library.observeFoldersInShares(ids).map { folders -> shares to folders }
                }
                .collect { (shares, folders) ->
                    val roots = folders.filter { it.parentId == null }.associateBy { it.shareId }
                    val nodes = shares.flatMap { share ->
                        val root = roots[share.id] ?: return@flatMap emptyList()
                        val children = folders.filter { it.parentId == root.id }.sortedBy { it.name.lowercase() }
                        listOf(TreeNode(root.id, share.name, depth = 0, fileCount = root.fileCount, isShare = true)) +
                            children.map { TreeNode(it.id, it.name, depth = 1, fileCount = it.fileCount, isShare = false) }
                    }
                    _uiState.update { it.copy(tree = nodes) }
                }
        }
    }

    /** Rows <-> tiles. Written to preferences; the collector above puts it back on the state. */
    fun toggleViewMode() {
        viewModelScope.launch { prefs.setBrowseViewMode(_uiState.value.viewMode.toggled()) }
    }

    private fun observeRoot() {
        viewModelScope.launch {
            combine(sources.observeServers(), sources.observeEnabledShares()) { servers, shares ->
                val byId = servers.associateBy { it.id }
                shares.map { s ->
                    BrowseRow.ShareRow(s.id, s.name, byId[s.serverId]?.name ?: "", s.freeBytes)
                }
            }.collect { rows ->
                _uiState.update { it.copy(title = "Browse", rows = rows, loaded = true, noSource = rows.isEmpty()) }
            }
        }
    }

    private fun observeFolder(id: Long) {
        viewModelScope.launch {
            combine(library.observeFolder(id), library.observeContents(id)) { folder, items ->
                folder to items
            }.collect { (folder, items) ->
                // The folder this screen IS was deleted — reachable now that
                // folders are targets, because a pick survives walking into
                // the thing that was picked. Nothing below can be drawn, so
                // the screen asks to be popped instead of showing a listing
                // of rows that are gone too.
                if (folder == null) {
                    if (_uiState.value.loaded) _uiState.update { it.copy(gone = true) }
                    return@collect
                }
                val shareName = library.shareLabel(folder.shareId).substringAfter(" · ")
                _uiState.update {
                    it.copy(
                        title = folder.name,
                        breadcrumb = (listOf(shareName) + folder.relPath.split('/').filter { p -> p.isNotEmpty() }).joinToString(" / "),
                        rows = items.map { item -> item.toRow() },
                        loaded = true,
                    )
                }
            }
        }
        refresh()
    }

    /** Re-list this folder from the share. */
    fun refresh() {
        val id = folderId ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(refreshing = true) }
            try {
                library.refreshFolder(id)
                _uiState.update { it.copy(refreshing = false, offlineMessage = null) }
            } catch (e: SmbFailure) {
                _uiState.update {
                    it.copy(refreshing = false, offlineMessage = "Couldn't reach the share. Showing what's saved here.")
                }
            }
        }
    }

    /** Root only: the folder id for a share's root, created on first visit. */
    fun openShare(shareId: Long, onReady: (folderId: Long) -> Unit) {
        viewModelScope.launch { onReady(library.rootFolder(shareId).id) }
    }

    // ── Multi-selection ────────────────────────────────────────────────
    //
    // The picks live in the app-scoped SelectionStore behind
    // SelectionPresenter, not here: this ViewModel is created per folderId,
    // so drilling into a subfolder builds a new one and anything held here
    // would be lost on exactly the gesture the feature exists for.

    /** Long press: arm selection mode and pick what was held, in one gesture. */
    fun beginSelection(row: BrowseRow) {
        selection.begin()
        toggleSelection(row)
    }

    fun toggleSelection(row: BrowseRow) {
        when (row) {
            is BrowseRow.FolderRow -> selection.toggleFolder(row.toPick())
            is BrowseRow.FileRow -> selection.toggleFile(row.toPick())
            // A selection lives inside one share, so the root's share rows are not pickable.
            is BrowseRow.ShareRow -> Unit
        }
    }

    /** Everything on this screen, leaving picks made elsewhere alone. */
    fun selectAllHere() {
        val rows = _uiState.value.rows
        selection.begin()
        selection.addAll(
            folders = rows.filterIsInstance<BrowseRow.FolderRow>().map { it.toPick() },
            files = rows.filterIsInstance<BrowseRow.FileRow>().map { it.toPick() },
        )
    }

    fun cancelSelection() = selection.cancel()

    /** Queue the batch and leave selection mode. */
    fun downloadSelection() {
        viewModelScope.launch { selection.download() }
    }

    // ── Managing things on the share (P12; folders in P13) ─────────────
    //
    // A picked FOLDER is a target in its own right. On the server that is
    // no harder than a file — one metadata operation moves a directory and
    // its whole subtree — but it means every verb here has to speak about
    // two kinds of thing, which is why the batch is a list of
    // [FileOpTarget] rather than a list of ids.

    private fun hintFor(sel: SelectionUiState?): String? = when {
        sel == null -> null
        sel.itemCount > 1 -> "Rename works on one at a time"
        else -> null
    }

    /** What is picked, as the repository wants it: ids that say what they point at. */
    private fun picked(): List<FileOpTarget>? {
        val sel = _uiState.value.selection ?: return null
        val targets = sel.pickedFolders.map { FileOpTarget.folder(it) } + sel.pickedFiles.map { FileOpTarget.file(it) }
        return targets.takeIf { it.isNotEmpty() }
    }

    fun startRename() {
        val target = picked()?.singleOrNull() ?: return
        viewModelScope.launch {
            val name = if (target.isFolder) library.folder(target.id)?.name else library.file(target.id)?.name
            _uiState.update { it.copy(renaming = RenameTarget(target, name ?: return@launch)) }
        }
    }

    fun rename(newName: String) {
        val target = _uiState.value.renaming ?: return
        _uiState.update { it.copy(renaming = null) }
        viewModelScope.launch {
            val result = fileOps.rename(target.target, newName)
            selection.cancel()
            report(result, verb = "rename", past = "Renamed")
        }
    }

    /**
     * A folder delete reaches through the subtree, so the dialog counts
     * through it too — the videos and bytes below a picked folder are what
     * is actually going, however few rows were tapped.
     */
    fun startDelete() {
        val targets = picked() ?: return
        viewModelScope.launch {
            val names = mutableListOf<String>()
            var videos = 0
            var bytes = 0L
            var folders = 0
            for (target in targets) {
                if (target.isFolder) {
                    val folder = library.folder(target.id) ?: continue
                    folders++
                    names += folder.name
                    val inside = library.filesUnder(target.id)
                    videos += inside.size
                    bytes += inside.sumOf { it.sizeBytes }
                } else {
                    val file = library.file(target.id) ?: continue
                    names += file.name
                    videos++
                    bytes += file.sizeBytes
                }
            }
            _uiState.update {
                it.copy(
                    confirmingDelete = DeleteTarget(
                        targets = targets,
                        names = names,
                        sizeLabel = formatBytes(bytes),
                        videoCount = videos,
                        folderCount = folders,
                    ),
                )
            }
        }
    }

    fun confirmDelete() {
        val target = _uiState.value.confirmingDelete ?: return
        _uiState.update { it.copy(confirmingDelete = null) }
        viewModelScope.launch {
            val result = fileOps.delete(target.targets)
            selection.cancel()
            report(result, verb = "delete", past = "Deleted")
        }
    }

    /** Open the destination picker, rooted where the user already is. */
    fun startMove() {
        val targets = picked() ?: return
        val here = folderId ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(moveSheet = sheetFor(here, chosenId = here, targets = targets)) }
        }
    }

    /** Walk into a folder. Walking in also chooses it — that is what walking in means here. */
    fun moveWalk(folderId: Long) {
        viewModelScope.launch {
            val targets = picked() ?: return@launch
            _uiState.update { it.copy(moveSheet = sheetFor(folderId, chosenId = folderId, targets = targets)) }
        }
    }

    fun moveUp() {
        viewModelScope.launch {
            val sheet = _uiState.value.moveSheet ?: return@launch
            val targets = picked() ?: return@launch
            val parent = library.folder(sheet.currentFolderId)?.parentId ?: return@launch
            _uiState.update { it.copy(moveSheet = sheetFor(parent, chosenId = parent, targets = targets)) }
        }
    }

    /** Choose a folder without walking into it. */
    fun moveChoose(chosenId: Long) {
        viewModelScope.launch {
            val sheet = _uiState.value.moveSheet ?: return@launch
            val targets = picked() ?: return@launch
            _uiState.update { it.copy(moveSheet = sheetFor(sheet.currentFolderId, chosenId = chosenId, targets = targets)) }
        }
    }

    // ── Making a folder that is not there yet ──────────────────────────

    /** Ask for the name. The sheet stays up behind the prompt. */
    fun startNewFolder() {
        val sheet = _uiState.value.moveSheet ?: return
        _uiState.update { it.copy(newFolderIn = sheet.currentFolderId) }
    }

    fun cancelNewFolder() = _uiState.update { it.copy(newFolderIn = null) }

    /**
     * Create it and choose it, without closing the sheet: the folder was
     * asked for as a destination, so landing back on the picker with it
     * already ticked is the shortest path to the move that prompted it.
     */
    fun createFolder(name: String) {
        val parentId = _uiState.value.newFolderIn ?: return
        _uiState.update { it.copy(newFolderIn = null) }
        viewModelScope.launch {
            val result = fileOps.createFolder(parentId, name)
            val targets = picked() ?: return@launch
            val made = result.done.firstOrNull()
            if (made == null) {
                // Back to the sheet with the reason on it. Not a snackbar:
                // see [MoveSheetState.error].
                val why = result.failures.firstOrNull()?.let { FileOpMessages.forFailure(it, "create") }
                _uiState.update {
                    it.copy(moveSheet = sheetFor(parentId, chosenId = parentId, targets = targets, error = why))
                }
                return@launch
            }
            _uiState.update { it.copy(moveSheet = sheetFor(parentId, chosenId = made.id, targets = targets)) }
        }
    }

    fun cancelRename() = _uiState.update { it.copy(renaming = null) }

    fun cancelDelete() = _uiState.update { it.copy(confirmingDelete = null) }

    fun dismissMove() = _uiState.update { it.copy(moveSheet = null) }

    fun confirmMove() {
        val sheet = _uiState.value.moveSheet ?: return
        val targets = picked() ?: return
        val from = folderId ?: return
        _uiState.update { it.copy(moveSheet = null) }
        viewModelScope.launch {
            val result = fileOps.move(targets, sheet.chosenFolderId)
            selection.cancel()
            report(
                result, verb = "move", past = "Moved", where = sheet.chosenName,
                // Only when everything made it: a half-done batch undone
                // halfway is a worse place to be than where it stopped.
                undo = if (result.ok && result.done.isNotEmpty()) UndoMove(result.done, from) else null,
            )
        }
    }

    /** Put them back. The inverse of a move is the same single rename. */
    fun undoMove() {
        val undo = _uiState.value.fileOpMessage?.undo ?: return
        _uiState.update { it.copy(fileOpMessage = null) }
        viewModelScope.launch {
            val result = fileOps.move(undo.targets, undo.backToFolderId)
            report(result, verb = "move", past = "Moved back")
        }
    }

    fun clearFileOpMessage() = _uiState.update { it.copy(fileOpMessage = null) }

    private fun report(result: FileOpResult, verb: String, past: String, where: String? = null, undo: UndoMove? = null) {
        val text = FileOpMessages.forResult(result, verb, past, where)
        // ONE message, always. A failure used to set a banner as well, so the
        // same sentence arrived twice — once floating and once in the layout.
        // A failure earns more TIME instead (see the screen's duration), not a
        // second copy of itself.
        _uiState.update { it.copy(fileOpMessage = FileOpMessage(text, undo, failed = !result.ok)) }
    }

    /**
     * The sheet as it looks with [chosenId] picked while looking at [currentId].
     *
     * A picked folder cannot be its own destination, nor can anything
     * inside it, so those rows are offered but not choosable. The test is on
     * PATHS rather than ids because the folder being aimed at may be several
     * levels down from the one that was picked.
     */
    private suspend fun sheetFor(
        currentId: Long,
        chosenId: Long,
        targets: List<FileOpTarget>,
        error: String? = null,
    ): MoveSheetState? {
        val current = library.folder(currentId) ?: return null
        val chosen = library.folder(chosenId) ?: return null
        val shareName = library.shareLabel(current.shareId).substringAfter(" · ")
        val source = folderId
        val movingPaths = targets.filter { it.isFolder }.mapNotNull { library.folder(it.id)?.relPath }
        fun insideAMovingFolder(relPath: String) =
            movingPaths.any { relPath == it || relPath.startsWith("$it/") }
        val currentBlocked = insideAMovingFolder(current.relPath)
        val chosenBlocked = insideAMovingFolder(chosen.relPath)
        return MoveSheetState(
            itemsLabel = FileOpMessages.subjectFor(targets),
            shareName = shareName,
            breadcrumb = (listOf(shareName) + current.relPath.split('/').filter { it.isNotEmpty() }).joinToString(" / "),
            currentFolderId = current.id,
            children = library.subfolders(current.id).map { f ->
                val blocked = insideAMovingFolder(f.relPath)
                MoveChild(
                    folderId = f.id,
                    name = f.name,
                    meta = when {
                        blocked -> "Being moved"
                        f.fileCount > 0 -> "${f.fileCount} videos · ${formatBytes(f.byteCount)}"
                        else -> null
                    },
                    enabled = !blocked,
                )
            },
            chosenFolderId = chosen.id,
            chosenName = chosen.name,
            canUp = current.parentId != null,
            currentChoosable = current.id != source && !currentBlocked,
            confirmEnabled = chosen.id != source && !chosenBlocked,
            note = when {
                currentBlocked -> "A folder can't move inside itself"
                current.id == source -> "They're already here"
                else -> null
            },
            error = error,
        )
    }

    private fun BrowseRow.FolderRow.toPick() =
        FolderPick(folderId = folderId, shareId = shareId, relPath = relPath, fileCount = fileCount, byteCount = byteCount, listed = listed)

    private fun BrowseRow.FileRow.toPick() =
        FilePick(fileId = fileId, shareId = shareId, folderRelPath = folderRelPath, sizeBytes = sizeBytes)

    private fun BrowseItem.toRow(): BrowseRow = when (this) {
        is BrowseItem.Folder -> BrowseRow.FolderRow(id, name, fileCount, byteCount, shareId, relPath, listed)
        is BrowseItem.File -> BrowseRow.FileRow(
            fileId = id,
            name = name,
            ext = MediaFileTypes.extensionOf(name),
            sizeBytes = sizeBytes,
            progressMs = progressMs,
            durationMs = durationMs,
            resolutionLabel = VideoInfo.resolutionLabelFor(width, height),
            shareId = shareId,
            folderRelPath = folderRelPath,
        )
    }
}
