package com.regolith.ui.titledetail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import com.regolith.data.db.MediaFileEntity
import com.regolith.data.fileops.FileOpsRepository
import com.regolith.data.repository.LibraryRepository
import com.regolith.data.repository.PhoneLibrary
import com.regolith.data.repository.PlaybackRepository
import com.regolith.domain.media.PhonePaths
import com.regolith.data.transfer.TransferRepository
import com.regolith.data.transfer.TransferRepository.Companion.causeEnum
import com.regolith.data.transfer.TransferRepository.Companion.statusEnum
import com.regolith.domain.artwork.ArtworkKind
import com.regolith.domain.artwork.ArtworkOwner
import com.regolith.domain.artwork.ArtworkRequest
import com.regolith.domain.media.MediaInfo
import com.regolith.domain.playback.VideoInfo
import com.regolith.domain.smb.SmbFailure
import com.regolith.player.MediaProbe
import com.regolith.domain.fileops.FileOpTarget
import com.regolith.ui.util.FileOpMessages
import com.regolith.ui.util.formatBytes
import com.regolith.ui.util.formatDurationShort
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Title Detail: one file, its facts, the one red action. The facts come
 * from the file row; the first visit runs the container probe once and
 * stores what it finds, so every later visit (and the Browse chips) is
 * instant.
 */
@UnstableApi
@HiltViewModel(assistedFactory = TitleDetailViewModel.Factory::class)
class TitleDetailViewModel @AssistedInject constructor(
    @Assisted private val fileId: Long,
    private val library: LibraryRepository,
    private val playback: PlaybackRepository,
    private val probe: MediaProbe,
    private val transfers: TransferRepository,
    private val fileOps: FileOpsRepository,
    private val phone: PhoneLibrary,
) : ViewModel() {

    @AssistedFactory
    interface Factory {
        fun create(fileId: Long): TitleDetailViewModel
    }

    private val _uiState = MutableStateFlow(TitleDetailUiState(fileId = fileId))
    val uiState: StateFlow<TitleDetailUiState> = _uiState

    private var probed = false

    init {
        viewModelScope.launch {
            transfers.observeForFile(fileId).collect { row ->
                _uiState.update { it.copy(transfer = row?.let { r -> TransferView(r.statusEnum(), r.bytesDone, r.totalBytes, r.causeEnum(), r.causeBytes) }) }
            }
        }
        viewModelScope.launch {
            library.observeFile(fileId).collect { file ->
                if (file == null) return@collect
                val progress = playback.progress(fileId)?.takeUnless { it.completed }
                val share = library.shareLabel(file.shareId).substringAfter(" · ")
                val isPhone = phone.isPhoneFile(fileId)
                val folderPath = file.relPath.substringBeforeLast('/', "")
                _uiState.update {
                    it.copy(
                        loaded = true,
                        title = file.name.substringBeforeLast('.'),
                        // The hero runs the full width of the window — 2076px unfolded —
                        // so it takes the 1280x720 backdrop, not the 320x180 tile thumb.
                        artwork = ArtworkRequest(ArtworkOwner.File(fileId), ArtworkKind.BACKDROP),
                        chips = chipsFor(file),
                        progressMs = progress?.positionMs?.takeIf { p -> p > 0 },
                        durationMs = progress?.durationMs ?: file.durationMs,
                        // A phone video's path is where it sits on the phone —
                        // "DCIM/Camera/" — not the absolute path it is keyed by.
                        path = if (isPhone) {
                            PhonePaths.display(folderPath) + "/"
                        } else {
                            "/$share/" + folderPath.let { p -> if (p.isEmpty()) "" else "$p/" }
                        },
                        phone = isPhone,
                        phoneFolder = if (isPhone) PhonePaths.display(folderPath).substringAfterLast('/') else "",
                        videoLine = if (file.probedAtMs != null) infoOf(file).videoLine else null,
                        audioLine = if (file.probedAtMs != null) infoOf(file).audioLine else null,
                        modifiedAtMs = file.modifiedAtMs,
                        fileName = file.name,
                        sizeLabel = formatBytes(file.sizeBytes),
                    )
                }
                if (file.probedAtMs == null && !probed) runProbe()
            }
        }
        viewModelScope.launch {
            library.observeFile(fileId).collect { file ->
                if (file == null) return@collect
                val folder = library.folder(file.folderId)
                val siblings = if (folder?.kind == com.regolith.domain.library.FolderKind.TITLE.name) {
                    library.filesInFolder(file.folderId).filter { it.id != fileId }
                } else {
                    emptyList()
                }
                _uiState.update {
                    it.copy(
                        siblings = siblings.map { s ->
                            SiblingFile(
                                fileId = s.id, name = s.name,
                                meta = listOfNotNull(s.durationMs?.let { d -> formatDurationShort(d) }, formatBytes(s.sizeBytes)).joinToString(" · "),
                                resolutionLabel = VideoInfo.resolutionLabelFor(s.width, s.height),
                                artwork = ArtworkRequest(ArtworkOwner.File(s.id), ArtworkKind.THUMB),
                            )
                        },
                    )
                }
            }
        }
    }

    /** "Keep on this device" and "Try again". */
    fun keepOnDevice() = viewModelScope.launch { transfers.start(fileId) }.let { }

    /** Cancel a transfer in flight, or remove the finished copy. The share is untouched either way. */
    fun removeFromDevice() = viewModelScope.launch { transfers.remove(fileId) }.let { }

    // ── Managing the file on the share (P12) ───────────────────────────
    //
    // Both verbs go through a dialog, and both report back onto this state
    // rather than throwing: the share refusing is an ordinary thing that
    // the screen has a sentence for.

    fun startRename() = _uiState.update { it.copy(renaming = true, fileOpError = null) }

    fun startDelete() = _uiState.update { it.copy(confirmingDelete = true, fileOpError = null) }

    fun dismissFileOp() = _uiState.update { it.copy(renaming = false, confirmingDelete = false) }

    fun dismissFileOpError() = _uiState.update { it.copy(fileOpError = null) }

    /** [newBaseName] is what the field holds: the name without its extension. */
    fun rename(newBaseName: String) {
        _uiState.update { it.copy(renaming = false) }
        viewModelScope.launch {
            val result = fileOps.rename(FileOpTarget.file(fileId), newBaseName)
            // The row keeps its id, so the screen's own flows redraw the new
            // name; only a failure needs saying.
            result.failures.firstOrNull()?.let { f ->
                _uiState.update { it.copy(fileOpError = FileOpMessages.forFailure(f, "rename")) }
            }
        }
    }

    fun confirmDelete() {
        _uiState.update { it.copy(confirmingDelete = false) }
        viewModelScope.launch {
            val result = fileOps.delete(listOf(FileOpTarget.file(fileId)))
            if (result.ok) {
                _uiState.update { it.copy(deleted = true) }
            } else {
                _uiState.update { it.copy(fileOpError = FileOpMessages.forFailure(result.failures.first(), "delete")) }
            }
        }
    }

    // ── A phone video (Phone storage) ──────────────────────────────────

    /** "Hide from Regolith": the file stays in its folder, and the screen leaves as if it were gone. */
    fun hideFromRegolith() {
        viewModelScope.launch {
            phone.hideFile(fileId)
            _uiState.update { it.copy(deleted = true) }
        }
    }

    /** Ask MediaStore for the system's delete sheet; the screen launches it. */
    fun startPhoneDelete() {
        viewModelScope.launch {
            val request = phone.deleteRequest(fileId)
            _uiState.update {
                if (request == null) it.copy(fileOpError = "This video is no longer on the phone.") else it.copy(phoneDeleteRequest = request)
            }
        }
    }

    /** The sheet is up; forget the request so a recomposition cannot launch it twice. */
    fun phoneDeleteLaunched() = _uiState.update { it.copy(phoneDeleteRequest = null) }

    /** The sheet came back. Approved means the file is gone; re-read the phone, then leave. */
    fun phoneDeleteAnswered(approved: Boolean) {
        if (!approved) return
        viewModelScope.launch {
            phone.sync()
            _uiState.update { it.copy(deleted = true) }
        }
    }

    private fun runProbe() {
        probed = true
        viewModelScope.launch {
            _uiState.update { it.copy(probing = true, probeError = null) }
            try {
                library.saveProbe(fileId, probe.probe(fileId))
                _uiState.update { it.copy(probing = false) }
            } catch (e: SmbFailure) {
                _uiState.update { it.copy(probing = false, probeError = "Couldn't reach the share to read the file.") }
            } catch (e: Exception) {
                _uiState.update { it.copy(probing = false, probeError = "Couldn't read this file's details.") }
            }
        }
    }

    private fun chipsFor(file: MediaFileEntity): List<String> = listOfNotNull(
        VideoInfo.resolutionLabelFor(file.width, file.height).ifEmpty { null },
        if (file.hdr == true) "HDR" else null,
        file.durationMs?.takeIf { it > 0 }?.let { formatDurationShort(it) },
        formatBytes(file.sizeBytes),
        VideoInfo.codecLabelFor(file.videoCodec).ifEmpty { null },
    )

    private fun infoOf(file: MediaFileEntity) = MediaInfo(
        durationMs = file.durationMs,
        width = file.width,
        height = file.height,
        rotationDegrees = file.rotationDegrees,
        frameRate = file.frameRate,
        videoMimeType = file.videoCodec,
        hdr = file.hdr ?: false,
        audioMimeType = file.audioCodec,
        audioChannels = file.audioChannels,
        audioSampleRate = file.audioSampleRate,
    )
}
