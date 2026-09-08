package com.regolith.ui.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.regolith.data.prefs.AppPreferences
import com.regolith.domain.playback.AbLoop
import com.regolith.player.PlaybackSession
import com.regolith.player.PlaybackState
import com.regolith.ui.navigation.RegolithKey
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Thin adapter between the Player screen and the app-owned
 * [PlaybackSession]. It holds no playback state of its own (guardrail G4).
 * Playback stops when the screen goes away; background audio and PiP will
 * change that later without touching the screen.
 */
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

    init {
        session.load(key.fileId, key.startMs)
    }

    fun togglePlayPause() = session.togglePlayPause()
    fun seekTo(ms: Long) = session.seekTo(ms)
    fun seekBy(deltaMs: Long) = session.seekBy(deltaMs)
    fun setSpeed(speed: Float) = session.setSpeed(speed)
    fun holdFast(hold: Boolean) = session.holdFast(hold)
    fun setHardwareDecoding(hardware: Boolean) = session.setHardwareDecoding(hardware)
    fun setScrubThumbnails(enabled: Boolean) = viewModelScope.launch { prefs.setScrubThumbnails(enabled) }.let { }
    fun tapLoopPoint() = session.tapLoopPoint()
    fun nudgeLoopA(deltaMs: Long = AbLoop.NUDGE_MS) = session.nudgeLoopA(deltaMs)
    fun nudgeLoopB(deltaMs: Long = AbLoop.NUDGE_MS) = session.nudgeLoopB(deltaMs)
    fun clearLoop() = session.clearLoop()
    fun playNext(fileId: Long) = session.load(fileId)
    fun onPause() = session.saveProgress()

    override fun onCleared() {
        session.stop()
    }
}
