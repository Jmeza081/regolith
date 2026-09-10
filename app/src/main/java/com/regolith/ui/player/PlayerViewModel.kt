package com.regolith.ui.player

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.regolith.data.prefs.AppPreferences
import com.regolith.domain.playback.AbLoop
import com.regolith.domain.playback.PlayerOrientation
import com.regolith.player.PlaybackSession
import com.regolith.player.PlaybackState
import com.regolith.ui.navigation.RegolithKey
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.stateIn
import kotlin.math.absoluteValue

/**
 * Thin adapter between the Player screen and the app-owned
 * [PlaybackSession]. It holds no playback state of its own (guardrail G4).
 * Playback stops when the screen goes away; background audio and PiP will
 * change that later without touching the screen.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@UnstableApi
@HiltViewModel(assistedFactory = PlayerViewModel.Factory::class)
class PlayerViewModel @AssistedInject constructor(
    @Assisted private val key: RegolithKey.Player,
    private val session: PlaybackSession,
    private val prefs: AppPreferences,
) : ViewModel() {

    @AssistedFactory
    interface Factory {
        fun create(key: RegolithKey.Player): PlayerViewModel
    }

    val state: StateFlow<PlaybackState> = session.state
    val player: StateFlow<ExoPlayer?> = session.player
    val scrubThumbnails: StateFlow<Boolean> = prefs.scrubThumbnails.stateIn(viewModelScope, SharingStarted.Eagerly, true)

    /** Settings › Playback › Keep playing, mirrored in the playback sheet. */
    val autoplayNext: StateFlow<Boolean> = prefs.autoplayNext.stateIn(viewModelScope, SharingStarted.Eagerly, true)

    fun setAutoplayNext(enabled: Boolean) = viewModelScope.launch { prefs.setAutoplayNext(enabled) }.let { }

    /** The player's rotation lock, from the playback sheet. */
    val orientation: StateFlow<PlayerOrientation> = prefs.playerOrientation.stateIn(viewModelScope, SharingStarted.Eagerly, PlayerOrientation.AUTO)

    fun setOrientation(orientation: PlayerOrientation) = viewModelScope.launch { prefs.setPlayerOrientation(orientation) }.let { }

    /** Settings › Playback › Don't ask first: no card, no countdown, the next file simply starts. */
    val autoplayImmediately: StateFlow<Boolean> = prefs.autoplayImmediately.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun setAutoplayImmediately(enabled: Boolean) = viewModelScope.launch { prefs.setAutoplayImmediately(enabled) }.let { }

    /** Null until read; false shows the gesture map once. */
    val gesturesSeen: StateFlow<Boolean?> = prefs.gesturesSeen.map<Boolean, Boolean?> { it }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun dismissGestureMap() = viewModelScope.launch { prefs.setGesturesSeen() }.let { }

    /** Where the finger is on the timeline, or null when not scrubbing. */
    private val scrubMs = MutableStateFlow<Long?>(null)

    /** The preview frame for the current scrub position; re-evaluated as frames arrive. */
    val scrubFrame: StateFlow<Bitmap?> = session.scrubThumbnails
        .flatMapLatest { thumbs -> combine(scrubMs, thumbs.updates) { ms, _ -> ms?.let { thumbs.nearest(it) } } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * The filmstrip under the hinge in flex mode: [STRIP_FRAMES] frames, one
     * per equal slice of the film, taken at the middle of each slice so the
     * first is not the black frame every film opens on.
     *
     * Same pipeline as the scrub preview -- ask for a position, redraw as
     * frames land -- so a strip costs nine key-frame seeks and no new
     * machinery. Nothing is asked for until [requestStrip] is called, which
     * only the flex layout does.
     */
    val strip: StateFlow<List<StripFrame>> = session.scrubThumbnails
        .flatMapLatest { thumbs ->
            combine(state, thumbs.updates) { s, _ ->
                stripPositions(s.durationMs).map { ms -> StripFrame(ms, thumbs.nearest(ms)) }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Load the filmstrip, nearest the playhead first: the frames beside where
     * you are are the ones you are about to look at, and the worker serves
     * the most recent request first.
     */
    fun requestStrip() {
        val thumbs = session.scrubThumbnails.value
        val here = state.value.positionMs
        stripPositions(state.value.durationMs).sortedByDescending { (it - here).absoluteValue }.forEach(thumbs::request)
    }

    init {
        session.load(key.fileId, key.startMs, key.queue.ifEmpty { null })
    }

    fun togglePlayPause() = session.togglePlayPause()
    fun seekTo(ms: Long) = session.seekTo(ms)
    fun seekBy(deltaMs: Long) = session.seekBy(deltaMs)
    fun setSpeed(speed: Float) = session.setSpeed(speed)
    fun holdFast(hold: Boolean) = session.holdFast(hold)
    fun setHardwareDecoding(hardware: Boolean) = session.setHardwareDecoding(hardware)
    fun setScrubThumbnails(enabled: Boolean) = session.setScrubThumbnails(enabled)
    fun tapLoopPoint() = session.tapLoopPoint()
    fun nudgeLoopA(deltaMs: Long = AbLoop.NUDGE_MS) = session.nudgeLoopA(deltaMs)
    fun nudgeLoopB(deltaMs: Long = AbLoop.NUDGE_MS) = session.nudgeLoopB(deltaMs)
    fun clearLoop() = session.clearLoop()
    fun playNext(fileId: Long) = session.load(fileId)
    fun onPause() = session.saveProgress()

    /** The scrubber reports where the finger is; ask for that frame. */
    fun onScrub(positionMs: Long) {
        scrubMs.value = positionMs
        session.scrubThumbnails.value.request(positionMs)
    }

    fun onScrubEnd() {
        scrubMs.value = null
    }

    override fun onCleared() {
        session.stop()
    }

    companion object {
        /** Frames across the flex-mode filmstrip. Nine fits the artboard's deck without crowding. */
        const val STRIP_FRAMES = 9

        /** The middle of each of [STRIP_FRAMES] equal slices, or empty until the duration is known. */
        fun stripPositions(durationMs: Long): List<Long> =
            if (durationMs <= 0) emptyList() else List(STRIP_FRAMES) { i -> (durationMs * (2 * i + 1)) / (2 * STRIP_FRAMES) }
    }
}

/** One frame of the flex-mode filmstrip: where it is in the film, and the picture once it has arrived. */
data class StripFrame(val positionMs: Long, val bitmap: Bitmap?)
