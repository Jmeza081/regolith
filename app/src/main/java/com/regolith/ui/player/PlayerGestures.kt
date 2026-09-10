package com.regolith.ui.player

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.unit.IntSize
import kotlin.math.abs

/**
 * Which third of the picture a gesture landed on (design section 10, whose
 * gesture map has always been drawn in three columns).
 *
 * Thirds, not halves: the middle needs a zone of its own for the full-screen
 * drag, and it also keeps a fast downward flick — the one that leaves the
 * player — away from the brightness and volume drags at the edges.
 */
enum class Zone { LEFT, MIDDLE, RIGHT }

/** Everything the gesture layer can tell the screen. All positions are fractions of the layer. */
interface PlayerGestureCallbacks {
    fun onTap(zone: Zone)
    fun onDoubleTap(zone: Zone)
    fun onLongPressStart()
    fun onPressReleased()
    /** A vertical drag began at [xFraction]; brightness on the left, volume on the right, full screen in the middle. */
    fun onDragStart(zone: Zone, xFraction: Float)
    /** Positive = finger moved down, as a fraction of the layer height. */
    fun onDrag(dyFraction: Float)
    /** [flingDown] is a fast downward flick. In the middle zone that is "leave the player". */
    fun onDragEnd(flingDown: Boolean)
    /** Pinch factor since the last event; > 1 spreads (fill), < 1 pinches (fit). */
    fun onZoom(factor: Float)
}

/**
 * The player's gesture map as one modifier. Taps, double-taps and
 * long-press come from Compose's tap detector; single-finger vertical
 * drags and two-finger pinches share a hand-rolled detector because the
 * stock transform detector would swallow the drags.
 */
fun Modifier.playerGestures(callbacks: PlayerGestureCallbacks): Modifier = this
    .pointerInput(callbacks) {
        detectTapGestures(
            onTap = { callbacks.onTap(zoneOf(it, size)) },
            onDoubleTap = { callbacks.onDoubleTap(zoneOf(it, size)) },
            onLongPress = { callbacks.onLongPressStart() },
            onPress = {
                tryAwaitRelease()
                callbacks.onPressReleased()
            },
        )
    }
    .pointerInput(callbacks) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val slop = viewConfiguration.touchSlop
            val startY = down.position.y
            var dragging = false
            var zooming = false
            var totalDx = 0f
            var prevDistance = -1f
            val velocity = VelocityTracker()

            while (true) {
                val event = awaitPointerEvent()
                val pressed = event.changes.filter { it.pressed }
                if (pressed.isEmpty()) break

                if (pressed.size >= 2) {
                    zooming = true
                    val d = (pressed[0].position - pressed[1].position).getDistance()
                    if (prevDistance > 0f && d > 0f) callbacks.onZoom(d / prevDistance)
                    prevDistance = d
                    event.changes.forEach { it.consume() }
                    continue
                }
                if (zooming) continue

                val c = pressed[0]
                val dy = c.position.y - c.previousPosition.y
                totalDx += c.position.x - c.previousPosition.x
                if (!dragging) {
                    val travelY = c.position.y - startY
                    if (abs(travelY) > slop && abs(travelY) > abs(totalDx)) {
                        dragging = true
                        callbacks.onDragStart(zoneOf(c.position, size), c.position.x / size.width)
                    }
                }
                if (dragging) {
                    callbacks.onDrag(dy / size.height)
                    velocity.addPosition(c.uptimeMillis, c.position)
                    c.consume()
                }
            }
            if (dragging) {
                val vy = velocity.calculateVelocity().y
                callbacks.onDragEnd(flingDown = vy > FLING_DOWN_PX_PER_S)
            }
        }
    }

private fun zoneOf(offset: Offset, size: IntSize): Zone = when {
    offset.x < size.width / 3f -> Zone.LEFT
    offset.x > size.width * 2f / 3f -> Zone.RIGHT
    else -> Zone.MIDDLE
}

private const val FLING_DOWN_PX_PER_S = 4_000f
