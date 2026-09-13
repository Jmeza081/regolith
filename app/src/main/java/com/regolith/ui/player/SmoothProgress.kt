package com.regolith.ui.player

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.state.ProgressStateWithTickCount
import androidx.media3.ui.compose.state.ProgressStateWithTickInterval
import androidx.media3.ui.compose.state.rememberProgressStateWithTickCount
import androidx.media3.ui.compose.state.rememberProgressStateWithTickInterval
import com.regolith.ui.util.formatClock

/**
 * The play position, sampled the way Media3's own Compose controls sample it.
 *
 * [com.regolith.player.PlaybackState.positionMs] is refreshed by a 250 ms
 * polling loop, which is fine for deciding which chapter you are in and far
 * too coarse to animate: the timeline advances in four visible steps a
 * second, and the clock changes a whole second up to 250 ms late, so the gap
 * between one second and the next alternates and reads as a stutter.
 *
 * Polling faster is the obvious fix and the wrong one — it redraws for
 * nothing between the moments anything actually changes. Media3 instead
 * works out *the next play position at which the display would change*,
 * converts it to real time by dividing by the playback speed, and sleeps
 * exactly that long before asking the player where it really is. Nothing is
 * ever guessed between samples, so there is no drift to correct and no
 * anchor to reset when playback pauses, stalls, loops or changes speed.
 *
 * Two schedulers, because the two readouts change at different rates:
 * [fraction] and [buffered] tick once per pixel the track is wide (tell it
 * how wide with [onTrackWidth]), [clockMs] once per whole second.
 *
 * Everything here is display-only. Seeking, chapter marking and saving
 * progress keep reading the player's own position, never one that has been
 * rounded to a pixel or floored to a second.
 *
 * One holder serves every timeline on the screen, so when two are up at once
 * — the chrome's bar and the chapter editor's — the last one to be measured
 * sets the tick rate for both. They are within a few pixels of each other's
 * width, so this costs nothing visible; it is only worth knowing before
 * adding a timeline of a very different size.
 */
@Stable
@androidx.annotation.OptIn(UnstableApi::class)
class SmoothProgress internal constructor(
    private val track: ProgressStateWithTickCount,
    private val clock: ProgressStateWithTickInterval,
) {
    /**
     * How far along the film, 0..1. Read it inside a draw block (as
     * [com.regolith.ui.components.Scrubber] does) so a tick repaints the
     * track without recomposing the chrome around it.
     */
    fun fraction(): Float = track.currentPositionProgress

    /** How much has buffered off the share, 0..1. Same rule as [fraction]. */
    fun buffered(): Float = track.bufferedPositionProgress

    /** The play position floored to a whole second, ready for [formatClock]. */
    fun clockMs(): Long = clock.currentPositionMs

    /**
     * How many pixels wide the track is. One update per pixel is the whole
     * point, so a track that has not been measured yet is ignored rather
     * than being told it is zero pixels wide.
     */
    fun onTrackWidth(px: Int) {
        if (px > 0) track.updateTotalTickCount(px)
    }
}

/**
 * Builds a [SmoothProgress] over [player], or over nothing while the player
 * is being swapped out — both Media3 state holders take a null player and
 * simply report zero until one arrives.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun rememberSmoothProgress(player: Player?): SmoothProgress {
    val track = rememberProgressStateWithTickCount(player)
    val clock = rememberProgressStateWithTickInterval(player, tickIntervalMs = 1_000L)
    return remember(track, clock) { SmoothProgress(track, clock) }
}

/**
 * An elapsed-time readout. Reading [ms] here rather than in the caller keeps
 * each second's change to this one [Text] instead of the whole row of chrome
 * it sits in.
 */
@Composable
fun PositionClock(
    ms: () -> Long,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Text(formatClock(ms()), style = style, color = color, modifier = modifier)
}
