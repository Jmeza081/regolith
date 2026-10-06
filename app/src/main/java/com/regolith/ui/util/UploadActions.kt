package com.regolith.ui.util

import com.regolith.data.spoof.SpoofMode
import com.regolith.data.artwork.PosterRepository
import com.regolith.data.transfer.UploadRepository
import com.regolith.domain.artwork.ExistingArtwork
import com.regolith.domain.artwork.FolderPosterOutcome
import com.regolith.domain.smb.SmbFailure
import com.regolith.domain.transfer.ConflictPolicy
import com.regolith.domain.transfer.UploadNames
import com.regolith.ui.components.PosterQuestion
import com.regolith.ui.components.UploadQuestion
import com.regolith.ui.components.UploadSection
import com.regolith.ui.components.UploadSectionAction
import com.regolith.ui.components.posterQuestion
import com.regolith.ui.components.uploadClash
import com.regolith.ui.components.uploadSection
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Sending things from the phone into one folder on a share (P16), and
 * making one picture its poster (P19), for every screen that shows a folder:
 * Browse, and a collection's wall in the Library.
 *
 * The same shape as [FileActions]: each ViewModel makes one for its folder
 * with its own scope ([Factory]), `UploadActionsHost` draws the sheets and
 * opens the pickers, and the screen puts [UploadActionsState.section] at the
 * top of its own list. Its messages go through the screen's [FileActions],
 * so the screen has one message line, not two.
 *
 * The queue itself is app-scoped (UploadRepository and its worker); this
 * only asks where the files would go, shows the rows for this folder, and
 * holds a pick while its one question is on screen.
 */
class UploadActions @AssistedInject constructor(
    @Assisted private val scope: CoroutineScope,
    @Assisted private val folderId: Long,
    @Assisted private val messages: FileActions,
    private val uploads: UploadRepository,
    private val posters: PosterRepository,
    private val spoof: SpoofMode,
) {
    @AssistedFactory
    interface Factory {
        fun create(scope: CoroutineScope, folderId: Long, messages: FileActions): UploadActions
    }

    private val _state = MutableStateFlow(UploadActionsState())
    val state: StateFlow<UploadActionsState> = _state.asStateFlow()

    /** Where uploads from here go, once known: null for a folder with no share behind it. */
    private var destination: UploadRepository.Destination? = null

    /** A pick waiting for its question to be answered. Not UI state: nothing draws it. */
    private var pendingPick: UploadRepository.Prepared? = null

    /** A poster picked for a folder with pictures of its own, while its question is up. Not UI state. */
    private var pendingPoster: String? = null

    init {
        scope.launch {
            val where = uploads.destinationOf(folderId) ?: return@launch
            destination = where
            _state.update { it.copy(canUpload = spoof.current == null, serverName = where.serverName, folderName = where.folderName) }
            uploads.observeFolder(folderId).collect { items ->
                _state.update { it.copy(section = uploadSection(items, where.serverName, where.folderName)) }
            }
        }
    }

    // Nothing goes up while spoof mode is on: the library can't be changed then.
    init {
        scope.launch {
            spoof.state.collect { spoofed -> _state.update { it.copy(canUpload = destination != null && spoofed == null) } }
        }
    }

    fun openSheet() = _state.update { it.copy(sheetOpen = true) }

    fun dismissSheet() = _state.update { it.copy(sheetOpen = false) }

    // ── files ──────────────────────────────────────────────────────────

    /**
     * The picker came back with [uris]. Picking IS the confirm — there is no
     * review step — unless a name is taken by a different file, which is the
     * one thing worth stopping to ask about.
     */
    fun onPicked(uris: List<String>) {
        if (uris.isEmpty()) return
        scope.launch {
            val prepared = uploads.prepare(folderId, uris) ?: return@launch
            if (prepared.files.isEmpty()) {
                messages.say("Couldn't read what you picked", failed = true)
                return@launch
            }
            if (prepared.needsAnswer) {
                pendingPick = prepared
                _state.update { it.copy(question = questionFor(prepared)) }
            } else {
                queue(prepared, ConflictPolicy.KEEP_BOTH)
            }
        }
    }

    fun answerUploadQuestion(policy: ConflictPolicy) {
        val prepared = pendingPick ?: return
        pendingPick = null
        _state.update { it.copy(question = null) }
        scope.launch { queue(prepared, policy) }
    }

    /** Backed out of the question: nothing is sent, and the access taken for the pick goes back. */
    fun dismissUploadQuestion() {
        pendingPick = null
        _state.update { it.copy(question = null) }
        scope.launch { uploads.discard() }
    }

    private suspend fun queue(prepared: UploadRepository.Prepared, policy: ConflictPolicy) {
        uploads.enqueue(prepared.destination.folderId, prepared.files, policy)
        if (prepared.unreadable > 0) {
            val n = prepared.unreadable
            messages.say(if (n == 1) "1 couldn't be read and was left out" else "$n couldn't be read and were left out", failed = true)
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

    // ── the folder's poster (P19) ──────────────────────────────────────
    //
    // One picture, written straight from here the way the poster editor
    // writes its poster.jpg — a few hundred KB, not a job for the upload
    // queue. The folder's tiles then redraw through artwork.replaced.

    /**
     * The photo picker came back with [uri] to be this folder's poster. It
     * goes straight up, unless the folder has a picture of its own already:
     * then the one question first, replace it or rename it out of the way.
     */
    fun onPosterPicked(uri: String) {
        val where = destination ?: return
        scope.launch {
            val art = try {
                posters.folderArtwork(folderId, uri)
            } catch (e: SmbFailure) {
                messages.say("Couldn't reach ${where.serverName}", failed = true)
                return@launch
            } ?: return@launch
            if (art.existing.isEmpty()) {
                uploadPoster(uri, ExistingArtwork.KEEP)
            } else {
                pendingPoster = uri
                val question = posterQuestion(
                    folderId = folderId,
                    folderName = art.folderName,
                    serverName = where.serverName,
                    pickedUri = uri,
                    existing = art.existing.map { it.name to it.sizeBytes },
                    keptNames = art.keptNames,
                    pickedName = art.picked.fileName,
                )
                _state.update { it.copy(posterQuestion = question) }
            }
        }
    }

    fun answerPosterQuestion(choice: ExistingArtwork) {
        val uri = pendingPoster ?: return
        pendingPoster = null
        _state.update { it.copy(posterQuestion = null) }
        scope.launch { uploadPoster(uri, choice) }
    }

    /** Backed out of the question: nothing is sent and nothing on the share changes. */
    fun dismissPosterQuestion() {
        pendingPoster = null
        _state.update { it.copy(posterQuestion = null) }
    }

    private suspend fun uploadPoster(uri: String, existing: ExistingArtwork) {
        val folderName = destination?.folderName ?: "this folder"
        val server = destination?.serverName ?: "the server"
        when (posters.uploadFolderPoster(folderId, uri, existing)) {
            FolderPosterOutcome.SAVED -> messages.say("Poster set for $folderName")
            FolderPosterOutcome.SAVED_STILL_TOO_BIG -> messages.say("Poster set for $folderName as a still: that GIF is over 8 MB")
            FolderPosterOutcome.SAVED_STILL_NESTED -> messages.say("Poster set for $folderName as a still: only top-level folder posters move")
            FolderPosterOutcome.UNREADABLE -> messages.say("Couldn't read that picture", failed = true)
            FolderPosterOutcome.READ_ONLY -> messages.say("$server is read-only, so the poster can't be saved there", failed = true)
            FolderPosterOutcome.UNREACHABLE -> messages.say("Couldn't reach $server", failed = true)
            FolderPosterOutcome.FAILED -> messages.say("The poster couldn't be saved. Try again", failed = true)
        }
    }

    // ── the section's own controls ─────────────────────────────────────

    fun onSectionAction(action: UploadSectionAction) {
        scope.launch {
            when (action) {
                UploadSectionAction.CANCEL_ALL -> uploads.cancelAll(folderId)
                UploadSectionAction.TRY_NOW -> uploads.tryNow()
                UploadSectionAction.RETRY_ALL -> uploads.retryAll(folderId)
                UploadSectionAction.CLEAR -> uploads.clearFinished(folderId)
            }
        }
    }

    fun retry(uploadId: Long) {
        scope.launch { uploads.retry(uploadId) }
    }

    /** The row's ✕: cancels a file still on its way, removes one that finished badly. */
    fun remove(uploadId: Long) {
        scope.launch { uploads.cancel(uploadId) }
    }
}

/** Everything [UploadActions] has on screen. */
data class UploadActionsState(
    /** A folder on a share, so uploading is offered. Never phone storage or the demo library. */
    val canUpload: Boolean = false,
    /** The server the files would go to, for the sheet's "· on TOWER". */
    val serverName: String? = null,
    val folderName: String? = null,
    /** The "Upload to…" sheet is up. */
    val sheetOpen: Boolean = false,
    /** Names in a pick are taken: the one question, asked before anything is sent. */
    val question: UploadQuestion? = null,
    /** A poster was picked for a folder that has pictures of its own already: replace or rename them. */
    val posterQuestion: PosterQuestion? = null,
    /** The uploads into this folder, for the top of the screen's list. Null when there are none. */
    val section: UploadSection? = null,
)
