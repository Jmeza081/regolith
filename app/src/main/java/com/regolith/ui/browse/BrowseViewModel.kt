package com.regolith.ui.browse

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.regolith.data.prefs.AppPreferences
import com.regolith.data.repository.LibraryRepository
import com.regolith.data.repository.SourceRepository
import com.regolith.domain.media.MediaFileTypes
import com.regolith.domain.model.BrowseItem
import com.regolith.domain.transfer.FilePick
import com.regolith.domain.transfer.FolderPick
import com.regolith.domain.playback.VideoInfo
import com.regolith.domain.smb.SmbFailure
import com.regolith.ui.util.SelectionPresenter
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
        viewModelScope.launch { selection.observe().collect { sel -> _uiState.update { it.copy(selection = sel) } } }
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
                if (folder == null) return@collect
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
