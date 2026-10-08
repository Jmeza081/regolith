package com.regolith.ui.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.regolith.data.prefs.AppPreferences
import com.regolith.player.PlaybackSession
import com.regolith.player.PlaybackState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * The mini player's side of the app-owned [PlaybackSession]: the film that
 * carries on once the player has been put away, small, above the nav (a bar
 * on a phone, a card in the corner of a wide window — see MiniPlayer.kt).
 *
 * One for the whole app, scoped to the Activity rather than to a screen,
 * because the mini player outlives every screen it floats over. Like
 * [PlayerViewModel] it holds no playback state of its own (guardrail G4).
 */
@UnstableApi
@HiltViewModel
class MiniPlayerViewModel @Inject constructor(
    private val session: PlaybackSession,
    prefs: AppPreferences,
) : ViewModel() {

    val state: StateFlow<PlaybackState> = session.state
    val player: StateFlow<ExoPlayer?> = session.player

    /** Settings › Playback › Picture-in-picture: whether leaving the app floats the film or pauses it. */
    val pictureInPicture: StateFlow<Boolean> = prefs.pictureInPicture.stateIn(viewModelScope, SharingStarted.Eagerly, true)

    /** The film is in its picture-in-picture window, which holds it when it ends (see [PlaybackSession.floating]). */
    fun setFloating(floating: Boolean) {
        session.floating = floating
    }

    fun togglePlayPause() = session.togglePlayPause()

    /** Previous, Next, and the step on to the next film in a queue. */
    fun play(fileId: Long) = session.load(fileId)

    /** A Moments reel's previous and next moments, for the card's buttons while one plays small. */
    fun reelPrevious() = session.reelPrevious()
    fun reelNext() = session.reelNext()

    /** The close button: the film stops, its place is saved, and the mini player goes. */
    fun close() = session.stop()
}
