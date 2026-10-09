package com.regolith.ui.components

import androidx.compose.foundation.gestures.DraggableState
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigationevent.DirectNavigationEventInput
import androidx.navigationevent.NavigationEvent
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner

/**
 * Swipe down to close, for a screen shown over another: the lightbox and a
 * story. The finger drives the screen's own leaving transition as a back
 * gesture of the screen's own, so the screen underneath shows through as it
 * goes, exactly as the system's back swipe does — Navigation 3 cannot tell
 * the two apart. Let go past [SETTLE] of the way, or flicked down, it
 * finishes; short of it, or flicked up, it comes back.
 *
 * The screen draws [pull] itself (moving or shrinking what it shows) and
 * says how far is all the way ([travelPx], half its height by default) and
 * how fast a flick is ([flickPxPerS]). Web analogy: a pointer handler that
 * scrubs a CSS view transition instead of playing it.
 */
@Stable
class SwipeToClose internal constructor(private val input: DirectNavigationEventInput) {
    /** How far the finger has pulled down, in pixels, while the swipe lasts. */
    var pull by mutableFloatStateOf(0f)
        private set

    /** A swipe is under way: the screen's chrome stands down meanwhile. */
    var swiping by mutableStateOf(false)
        private set

    /** The pull that carries the screen all the way out, in pixels. */
    var travelPx: Float = 1f

    /** A release faster than this, in pixels a second, decides by its direction alone. */
    var flickPxPerS: Float = 1f

    /** How far out the screen is, 0 to 1. */
    val fraction: Float get() = (pull / travelPx).coerceIn(0f, 1f)

    internal val draggable = DraggableState { delta ->
        pull = (pull + delta).coerceAtLeast(0f)
        if (!swiping && pull > 0f) {
            swiping = true
            input.backStarted(event(0f))
        }
        if (swiping) input.backProgressed(event(FlightEasing.timeFor(fraction)))
    }

    /** The finger is up at [velocity]: finish or come back. True when the screen is closing. */
    internal fun release(velocity: Float, onClosing: () -> Unit): Boolean {
        if (!swiping) return false
        swiping = false
        val carried = fraction
        pull = 0f
        val finish = when {
            velocity > flickPxPerS -> true
            velocity < -flickPxPerS -> false
            else -> carried >= SETTLE
        }
        if (finish) {
            onClosing()
            input.backCompleted()
        } else {
            input.backCancelled()
        }
        return finish
    }

    private fun event(progress: Float) = NavigationEvent(touchX = 0f, touchY = 0f, progress = progress, swipeEdge = NavigationEvent.EDGE_NONE)

    companion object {
        /** Let go past this much of the way, the screen goes on out; short of it, it comes back. */
        const val SETTLE = 0.3f

        /** How fast a flick is, in dp a second. */
        val FLICK = 1000.dp
    }
}

/**
 * A [SwipeToClose] wired to the screen's navigation events, or null where
 * there are none to drive (a preview).
 */
@Composable
fun rememberSwipeToClose(): SwipeToClose? {
    val dispatcher = LocalNavigationEventDispatcherOwner.current?.navigationEventDispatcher ?: return null
    val input = remember { DirectNavigationEventInput() }
    DisposableEffect(dispatcher) {
        dispatcher.addInput(input)
        onDispose { dispatcher.removeInput(input) }
    }
    return remember(input) { SwipeToClose(input) }
}

/**
 * The vertical drag that drives [swipe]. [onStart] runs as a finger starts
 * dragging (a story pauses), [onEnd] as it lifts, and [onClosing] just
 * before a released swipe finishes the screen.
 */
fun Modifier.swipeToClose(
    swipe: SwipeToClose?,
    enabled: Boolean = true,
    onStart: () -> Unit = {},
    onEnd: () -> Unit = {},
    onClosing: () -> Unit = {},
): Modifier = if (swipe == null) {
    this
} else {
    draggable(
        state = swipe.draggable,
        orientation = Orientation.Vertical,
        enabled = enabled,
        onDragStarted = { onStart() },
        onDragStopped = { velocity ->
            onEnd()
            swipe.release(velocity, onClosing)
        },
    )
}
