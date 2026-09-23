package com.regolith.ui.poster

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import com.regolith.data.artwork.PosterRepository
import com.regolith.domain.artwork.CropRect
import com.regolith.domain.artwork.PosterSaveOutcome
import com.regolith.player.Media3Frames
import com.regolith.player.PlaybackSession
import com.regolith.ui.navigation.RegolithKey
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.roundToLong

/**
 * The poster editor's state and actions (see [PosterEditorUiState]).
 *
 * Frames come from [Media3Frames.openExact]: the real frame at the real
 * time, at full resolution, read off the share. That is slower than the
 * player's own picture (a second or two per frame over SMB), so requests
 * are "latest wins" — scrubbing past ten positions decodes the last one,
 * not all ten — and the previous frame stays on screen while the next one
 * comes.
 */
@UnstableApi
@HiltViewModel(assistedFactory = PosterEditorViewModel.Factory::class)
class PosterEditorViewModel @AssistedInject constructor(
    @Assisted private val key: RegolithKey.PosterEditor,
    private val posters: PosterRepository,
    frames: Media3Frames,
    session: PlaybackSession,
) : ViewModel() {

    @AssistedFactory
    interface Factory {
        fun create(key: RegolithKey.PosterEditor): PosterEditorViewModel
    }

    private val _state = MutableStateFlow(PosterEditorUiState(positionMs = key.positionMs))
    val state: StateFlow<PosterEditorUiState> = _state.asStateFlow()

    private val source = frames.openExact(key.fileId)
    private val wanted = MutableStateFlow(key.positionMs)

    init {
        // The editor is only opened from the player, so the playback session
        // still holds this film: its title, runtime and frame rate are there
        // without another trip to the share.
        val playing = session.state.value.takeIf { it.fileId == key.fileId }
        _state.update {
            it.copy(
                title = playing?.title.orEmpty(),
                durationMs = playing?.durationMs ?: 0L,
                frameStepMs = playing?.video?.frameRate?.let { fps -> (1000f / fps).roundToLong().coerceAtLeast(1L) } ?: PosterEditorUiState.DEFAULT_FRAME_STEP_MS,
            )
        }
        viewModelScope.launch { _state.update { it.copy(target = posters.target(key.fileId)) } }
        viewModelScope.launch {
            // collectLatest cancels a decode that is no longer wanted.
            wanted.collectLatest { ms ->
                _state.update { it.copy(loadingFrame = true, frameFailed = false) }
                val grabbed = source.frameAt(ms)
                _state.update {
                    if (grabbed == null) {
                        it.copy(loadingFrame = false, frameFailed = true)
                    } else {
                        it.copy(frame = grabbed.bitmap, loadingFrame = false)
                    }
                }
            }
        }
    }

    /** Show the frame at [ms] (clamped to the film). */
    fun seekTo(ms: Long) {
        val d = _state.value.durationMs
        val clamped = if (d > 0) ms.coerceIn(0L, d - 1) else ms.coerceAtLeast(0L)
        _state.update { it.copy(positionMs = clamped, saveError = null) }
        wanted.value = clamped
    }

    fun seekBy(deltaMs: Long) = seekTo(_state.value.positionMs + deltaMs)

    /** One frame forward (1) or back (-1). */
    fun stepFrames(frames: Int) = seekBy(frames * _state.value.frameStepMs)

    /**
     * Write the poster. [crop] is in the frame's own pixels. With [replace]
     * false an existing poster.jpg stops the save and raises the confirm
     * dialog instead; the dialog's Replace calls this again with true.
     */
    fun save(crop: CropRect, replace: Boolean = false) {
        val s = _state.value
        val frame = s.frame ?: return
        if (s.saving) return
        _state.update { it.copy(saving = true, confirmReplace = false, saveError = null) }
        viewModelScope.launch {
            val outcome = posters.save(key.fileId, frame, crop, replace)
            _state.update {
                when (outcome) {
                    PosterSaveOutcome.SAVED -> it.copy(saving = false, done = true)
                    PosterSaveOutcome.EXISTS -> it.copy(saving = false, confirmReplace = true)
                    PosterSaveOutcome.READ_ONLY -> it.copy(saving = false, saveError = "This share is read-only, so the poster can’t be saved there.")
                    PosterSaveOutcome.UNREACHABLE -> it.copy(saving = false, saveError = "Couldn’t reach the server. Check the connection and try again.")
                    PosterSaveOutcome.FAILED -> it.copy(saving = false, saveError = "The poster couldn’t be saved. Try again.")
                }
            }
        }
    }

    /** The confirm dialog's "keep" answer. */
    fun keepExisting() = _state.update { it.copy(confirmReplace = false) }

    override fun onCleared() {
        source.close()
    }
}
