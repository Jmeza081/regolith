package com.regolith.ui.titledetail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import com.regolith.data.db.MediaFileEntity
import com.regolith.data.repository.LibraryRepository
import com.regolith.data.repository.PlaybackRepository
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
                _uiState.update {
                    it.copy(
                        loaded = true,
                        title = file.name.substringBeforeLast('.'),
                        artwork = ArtworkRequest(ArtworkOwner.File(fileId), ArtworkKind.THUMB),
                        chips = chipsFor(file),
                        progressMs = progress?.positionMs?.takeIf { p -> p > 0 },
                        durationMs = progress?.durationMs ?: file.durationMs,
                        path = "/$share/" + file.relPath.substringBeforeLast('/', "").let { p -> if (p.isEmpty()) "" else "$p/" },
                        videoLine = if (file.probedAtMs != null) infoOf(file).videoLine else null,
                        audioLine = if (file.probedAtMs != null) infoOf(file).audioLine else null,
                        modifiedAtMs = file.modifiedAtMs,
                    )
                }
                if (file.probedAtMs == null && !probed) runProbe()
            }
        }
    }

    /** "Keep on this device" and "Try again". */
    fun keepOnDevice() = viewModelScope.launch { transfers.start(fileId) }.let { }

    /** Cancel a transfer in flight, or remove the finished copy. The share is untouched either way. */
    fun removeFromDevice() = viewModelScope.launch { transfers.remove(fileId) }.let { }

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
        frameRate = file.frameRate,
        videoMimeType = file.videoCodec,
        hdr = file.hdr ?: false,
        audioMimeType = file.audioCodec,
        audioChannels = file.audioChannels,
        audioSampleRate = file.audioSampleRate,
    )
}
