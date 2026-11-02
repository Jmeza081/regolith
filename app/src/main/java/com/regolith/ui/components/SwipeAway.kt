package com.regolith.ui.components

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.DraggableState
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.dismiss
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.sign

/**
 * Swipe sideways to send something away, either way: a message capsule, the
 * mini player. It follows the finger and fades as it goes. Let go past
 * [SETTLE] of its own width, or flick it, and it carries on out and
 * `onSwiped` runs. Short of that it springs back.
 *
 * This is not [SwipeToClose], which drives a screen's back transition. This
 * one only moves the thing itself, and the caller decides what "away" means
 * (dismiss the message, stop the film). Web analogy: a pointer-events handler
 * that sets `transform: translateX()` and `opacity`, then calls `onDismiss`.
 *
 * Built on plain `draggable` + `animate` rather than Material's
 * `SwipeToDismissBox` or `anchoredDraggable`. Those want a list-row-shaped
 * layout with a background slot, and an API that has changed shape between
 * Compose releases. This is the whole gesture in one small file.
 */
@Stable
class SwipeAway internal constructor(private val flickPxPerS: Float) {
    /** How far the finger has moved it, in pixels; negative is toward the start. */
    var offset by mutableFloatStateOf(0f)
        private set

    internal var widthPx by mutableFloatStateOf(0f)

    /** How far gone it is, 0 to 1: drawn as the fade. */
    val fraction: Float get() = if (widthPx > 0f) (abs(offset) / widthPx).coerceIn(0f, 1f) else 0f

    internal val draggable = DraggableState { delta -> offset += delta }

    /**
     * The finger is up at [velocity]. A flick goes the way it was flicked.
     * A slow release goes on out past [SETTLE], and comes back short of it.
     * The settle runs through the draggable's own mutex, so a finger landing
     * again mid-flight takes over cleanly. A cancelled settle never calls
     * [onSwiped].
     */
    internal suspend fun release(velocity: Float, onSwiped: () -> Unit) {
        val direction = when {
            abs(velocity) > flickPxPerS -> sign(velocity)
            fraction >= SETTLE -> sign(offset)
            else -> 0f
        }
        val target = direction * widthPx
        draggable.drag {
            var last = offset
            val spec = if (direction == 0f) spring<Float>() else tween<Float>(OUT_MS)
            animate(offset, target, initialVelocity = velocity, animationSpec = spec) { value, _ ->
                dragBy(value - last)
                last = value
            }
        }
        if (direction != 0f) onSwiped()
    }

    companion object {
        /** Let go past this much of its width and it goes; short of it, it comes back. */
        const val SETTLE = 0.35f

        /** A release faster than this decides by its direction alone, in dp a second. */
        val FLICK = 800.dp

        private const val OUT_MS = 160
    }
}

/** A [SwipeAway] for one thing on screen. A new [key] (a new message) starts it back in place. */
@Composable
fun rememberSwipeAway(key: Any? = Unit): SwipeAway {
    val flick = with(LocalDensity.current) { SwipeAway.FLICK.toPx() }
    return remember(key, flick) { SwipeAway(flick) }
}

/**
 * Makes this node swipeable sideways with [swipe]. Put it **first** in the
 * modifier chain, so the shadow, background and contents all move and fade
 * together. Taps still reach the node's own `clickable`s: a drag only takes
 * over once the finger has moved past the touch slop.
 *
 * [dismissLabel] is what TalkBack offers as this node's Dismiss action,
 * since a swipe is the one thing a screen-reader user can't do.
 */
fun Modifier.swipeAway(
    swipe: SwipeAway,
    dismissLabel: String,
    enabled: Boolean = true,
    onSwiped: () -> Unit,
): Modifier = this
    .onSizeChanged { swipe.widthPx = it.width.toFloat() }
    .graphicsLayer {
        translationX = swipe.offset
        alpha = 1f - swipe.fraction
    }
    .semantics {
        if (enabled) {
            dismiss(dismissLabel) {
                onSwiped()
                true
            }
        }
    }
    .draggable(
        state = swipe.draggable,
        orientation = Orientation.Horizontal,
        enabled = enabled,
        onDragStopped = { velocity -> swipe.release(velocity, onSwiped) },
    )
