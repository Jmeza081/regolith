package com.regolith.ui.browse

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.regolith.data.artwork.ArtworkRepository
import com.regolith.data.artwork.PosterRepository
import com.regolith.data.prefs.AppPreferences
import com.regolith.data.repository.LibraryRepository
import com.regolith.data.repository.SourceRepository
import com.regolith.data.transfer.UploadRepository
import com.regolith.domain.artwork.ArtworkOwner
import com.regolith.domain.artwork.ExistingArtwork
import com.regolith.domain.artwork.FolderPosterOutcome
import com.regolith.domain.media.MediaFileTypes
import com.regolith.domain.media.OtherFiles
import com.regolith.domain.model.BrowseItem
import com.regolith.domain.transfer.FilePick
import com.regolith.domain.transfer.FolderPick
import com.regolith.domain.playback.VideoInfo
import com.regolith.domain.smb.SmbFailure
import com.regolith.domain.transfer.ConflictPolicy
import com.regolith.domain.transfer.UploadNames
import com.regolith.ui.util.FileActions
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
    private val uploads: UploadRepository,
    private val artwork: ArtworkRepository,
    private val posters: PosterRepository,
    fileActionsFactory: FileActions.Factory,
) : ViewModel() {

    @AssistedFactory
    interface Factory {
        fun create(folderId: Long?): BrowseViewModel
    }

    private val _uiState = MutableStateFlow(BrowseUiState())
    val uiState: StateFlow<BrowseUiState> = _uiState

    /**
     * Rename, move and delete for the picks, and the screen's one message
     * line: shared with the Library, drawn by `FileActionsHost`.
     */
    val fileActions: FileActions = fileActionsFactory.create(viewModelScope)

    /**
     * How many times each owner's picture has been replaced while this
     * screen was alive: the [com.regolith.domain.artwork.ArtworkRequest.revision]
     * its tile asks with. Only owners that changed are in it.
     */
    private val artworkRevisions = MutableStateFlow<Map<ArtworkOwner, Int>>(emptyMap())

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
        // A listing of this folder (the refresh below, an upload landing)
        // can find that a picture on screen was replaced on the share. Its
        // tile has drawn the old one already, and an image only loads again
        // when its request changes — so the owner's revision goes up.
        viewModelScope.launch {
            artwork.replaced.collect { owners ->
                artworkRevisions.update { current -> current + owners.associateWith { (current[it] ?: 0) + 1 } }
            }
        }
        viewModelScope.launch {
            combine(library.observeFolder(id), library.observeContents(id), artworkRevisions) { folder, items, revisions ->
                Triple(folder, items, revisions)
            }.collect { (folder, items, revisions) ->
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
                        rows = items.map { item -> item.toRow(revisions) },
                        loaded = true,
                    )
                }
            }
        }
        refresh()
        observeUploads(id)
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
            // Not yet: picking the files that are not videos is the next step
            // of the Library editing plan (LIBRARY_EDITING_PLAN.md).
            is BrowseRow.OtherRow -> Unit
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

    // Rename, move and delete live in [fileActions], shared with the Library.

    // ── Uploads (P16) ──────────────────────────────────────────────────
    //
    // The queue itself is app-scoped (UploadRepository and its worker); this
    // ViewModel only asks where the files would go, shows the rows for THIS
    // folder, and holds a pick while its one question is on screen.

    /** Where uploads from here go, once known: null for a folder with no share behind it. */
    private var destination: UploadRepository.Destination? = null

    /** A pick waiting for its question to be answered. Not UI state: nothing draws it. */
    private var pendingPick: UploadRepository.Prepared? = null

    private fun observeUploads(id: Long) {
        viewModelScope.launch {
            val where = uploads.destinationOf(id) ?: return@launch
            destination = where
            _uiState.update { it.copy(canUpload = true, uploadServer = where.serverName) }
            uploads.observeFolder(id).collect { items ->
                _uiState.update { it.copy(uploads = uploadSection(items, where.serverName, where.folderName)) }
            }
        }
    }

    fun openUploadSheet() = _uiState.update { it.copy(uploadSheet = true) }

    fun dismissUploadSheet() = _uiState.update { it.copy(uploadSheet = false) }

    /**
     * The picker came back with [uris]. Picking IS the confirm — there is no
     * review step — unless a name is taken by a different file, which is the
     * one thing worth stopping to ask about.
     */
    fun onPicked(uris: List<String>) {
        val id = folderId ?: return
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val prepared = uploads.prepare(id, uris) ?: return@launch
            if (prepared.files.isEmpty()) {
                fileActions.say("Couldn't read what you picked", failed = true)
                return@launch
            }
            if (prepared.needsAnswer) {
                pendingPick = prepared
                _uiState.update { it.copy(uploadQuestion = questionFor(prepared)) }
            } else {
                queue(prepared, ConflictPolicy.KEEP_BOTH)
            }
        }
    }

    fun answerUploadQuestion(policy: ConflictPolicy) {
        val prepared = pendingPick ?: return
        pendingPick = null
        _uiState.update { it.copy(uploadQuestion = null) }
        viewModelScope.launch { queue(prepared, policy) }
    }

    /** Backed out of the question: nothing is sent, and the access taken for the pick goes back. */
    fun dismissUploadQuestion() {
        pendingPick = null
        _uiState.update { it.copy(uploadQuestion = null) }
        viewModelScope.launch { uploads.discard() }
    }

    private suspend fun queue(prepared: UploadRepository.Prepared, policy: ConflictPolicy) {
        uploads.enqueue(prepared.destination.folderId, prepared.files, policy)
        if (prepared.unreadable > 0) {
            val n = prepared.unreadable
            fileActions.say(if (n == 1) "1 couldn't be read and was left out" else "$n couldn't be read and were left out", failed = true)
        }
    }

    private fun questionFor(prepared: UploadRepository.Prepared) = UploadQuestion(
        folderName = prepared.destination.folderName,
        serverName = prepared.destination.serverName,
        picked = prepared.files.size,
        clashes = prepared.clashes.map { clash ->
            uploadClash(
                name = clash.file.name,
                uri = clash.file.uri,
                sameFile = clash.sameFile,
                existingSize = clash.existingSize,
                // The first free number is only known when the file goes; this
                // is what it will almost always be.
                keptName = UploadNames.keepBoth(clash.file.name, setOf(clash.file.name)),
            )
        },
    )

    // ── Folder poster (P19) ────────────────────────────────────────────
    //
    // One picture, written straight from here the way the poster editor
    // writes its poster.jpg — a few hundred KB, not a job for the upload
    // queue. The folder's tiles then redraw through artwork.replaced.

    /** A poster picked for a folder with pictures of its own, while its question is up. Not UI state. */
    private var pendingPoster: String? = null

    /**
     * The photo picker came back with [uri] to be this folder's poster. It
     * goes straight up, unless the folder has a picture of its own already:
     * then the one question first, replace it or rename it out of the way.
     */
    fun onPosterPicked(uri: String) {
        val id = folderId ?: return
        val where = destination ?: return
        viewModelScope.launch {
            val art = try {
                posters.folderArtwork(id, uri)
            } catch (e: SmbFailure) {
                fileActions.say("Couldn't reach ${where.serverName}", failed = true)
                return@launch
            } ?: return@launch
            if (art.existing.isEmpty()) {
                uploadPoster(id, uri, ExistingArtwork.KEEP)
            } else {
                pendingPoster = uri
                val question = posterQuestion(
                    folderId = id,
                    folderName = art.folderName,
                    serverName = where.serverName,
                    pickedUri = uri,
                    existing = art.existing.map { it.name to it.sizeBytes },
                    keptNames = art.keptNames,
                    pickedName = art.picked.fileName,
                )
                _uiState.update { it.copy(posterQuestion = question) }
            }
        }
    }

    fun answerPosterQuestion(choice: ExistingArtwork) {
        val id = folderId ?: return
        val uri = pendingPoster ?: return
        pendingPoster = null
        _uiState.update { it.copy(posterQuestion = null) }
        viewModelScope.launch { uploadPoster(id, uri, choice) }
    }

    /** Backed out of the question: nothing is sent and nothing on the share changes. */
    fun dismissPosterQuestion() {
        pendingPoster = null
        _uiState.update { it.copy(posterQuestion = null) }
    }

    private suspend fun uploadPoster(id: Long, uri: String, existing: ExistingArtwork) {
        val folderName = destination?.folderName ?: "this folder"
        val server = destination?.serverName ?: "the server"
        when (posters.uploadFolderPoster(id, uri, existing)) {
            FolderPosterOutcome.SAVED -> fileActions.say("Poster set for $folderName")
            FolderPosterOutcome.SAVED_STILL_TOO_BIG -> fileActions.say("Poster set for $folderName as a still: that GIF is over 8 MB")
            FolderPosterOutcome.SAVED_STILL_NESTED -> fileActions.say("Poster set for $folderName as a still: only top-level folder posters move")
            FolderPosterOutcome.UNREADABLE -> fileActions.say("Couldn't read that picture", failed = true)
            FolderPosterOutcome.READ_ONLY -> fileActions.say("$server is read-only, so the poster can't be saved there", failed = true)
            FolderPosterOutcome.UNREACHABLE -> fileActions.say("Couldn't reach $server", failed = true)
            FolderPosterOutcome.FAILED -> fileActions.say("The poster couldn't be saved. Try again", failed = true)
        }
    }

    fun onUploadAction(action: UploadSectionAction) {
        val id = folderId ?: return
        viewModelScope.launch {
            when (action) {
                UploadSectionAction.CANCEL_ALL -> uploads.cancelAll(id)
                UploadSectionAction.TRY_NOW -> uploads.tryNow()
                UploadSectionAction.RETRY_ALL -> uploads.retryAll(id)
                UploadSectionAction.CLEAR -> uploads.clearFinished(id)
            }
        }
    }

    fun retryUpload(uploadId: Long) {
        viewModelScope.launch { uploads.retry(uploadId) }
    }

    /** The row's ✕: cancels a file still on its way, removes one that finished badly. */
    fun removeUpload(uploadId: Long) {
        viewModelScope.launch { uploads.cancel(uploadId) }
    }

    private fun BrowseRow.FolderRow.toPick() =
        FolderPick(folderId = folderId, shareId = shareId, relPath = relPath, fileCount = fileCount, byteCount = byteCount, listed = listed)

    private fun BrowseRow.FileRow.toPick() =
        FilePick(fileId = fileId, shareId = shareId, folderRelPath = folderRelPath, sizeBytes = sizeBytes)

    private fun BrowseItem.toRow(revisions: Map<ArtworkOwner, Int>): BrowseRow = when (this) {
        is BrowseItem.Folder -> BrowseRow.FolderRow(
            id, name, fileCount, byteCount, shareId, relPath, listed,
            artworkRevision = revisions[ArtworkOwner.Folder(id)] ?: 0,
        )
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
            artworkRevision = revisions[ArtworkOwner.File(id)] ?: 0,
        )
        is BrowseItem.Other -> BrowseRow.OtherRow(
            otherId = id, name = name, kind = OtherFiles.kindOf(name), sizeBytes = sizeBytes,
            shareId = shareId, folderRelPath = folderRelPath,
        )
    }
}
