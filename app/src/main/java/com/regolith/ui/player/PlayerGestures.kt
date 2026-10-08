package com.regolith.ui.player

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.unit.IntSize
import kotlin.math.abs

/**
 * Which zone of the picture a gesture landed on (design section 10, whose
 * gesture map has always been drawn in three columns).
 *
 * [SIDE_ZONE] down each edge and the rest in the middle, rather than even
 * thirds. Brightness and volume are the two gestures you least want by
 * accident — they change something the film cannot undo — so reaching them
 * should take aim. The middle keeps the full-screen drag, the dismiss and
 * play/pause, which are the ones worth having under any thumb.
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
    /**
     * Positive = finger moved down, as a fraction of the layer's height.
     * Measured on the window, not on the layer, so it stays true while the
     * picture the layer sits on moves and shrinks under the finger.
     */
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
 *
 * A drag is followed on the window rather than on the layer. The swipe down
 * into the mini player moves and shrinks the picture under the finger, and
 * measured on the moving layer the finger would hardly seem to move at all.
 */
@Composable
fun Modifier.playerGestures(callbacks: PlayerGestureCallbacks): Modifier {
    val layer = remember { LayerPlace() }
    return this
        .onPlaced { layer.coordinates = it }
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
                val start = layer.window(down.position)
                var last = start
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
                    val at = layer.window(c.position)
                    val dy = at.y - last.y
                    totalDx += at.x - last.x
                    last = at
                    if (!dragging) {
                        val travelY = at.y - start.y
                        if (abs(travelY) > slop && abs(travelY) > abs(totalDx)) {
                            dragging = true
                            callbacks.onDragStart(zoneOf(c.position, size), c.position.x / size.width)
                        }
                    }
                    if (dragging) {
                        callbacks.onDrag(dy / size.height)
                        velocity.addPosition(c.uptimeMillis, at)
                        c.consume()
                    }
                }
                if (dragging) {
                    val vy = velocity.calculateVelocity().y
                    callbacks.onDragEnd(flingDown = vy > FLING_DOWN_PX_PER_S)
                }
            }
        }
}

/** Where the gesture layer is now, transforms and all: the window's view of a point on it. */
private class LayerPlace {
    var coordinates: LayoutCoordinates? = null

    fun window(local: Offset): Offset = coordinates?.takeIf { it.isAttached }?.localToWindow(local) ?: local
}

private fun zoneOf(offset: Offset, size: IntSize): Zone = when {
    offset.x < size.width * SIDE_ZONE -> Zone.LEFT
    offset.x > size.width * (1f - SIDE_ZONE) -> Zone.RIGHT
    else -> Zone.MIDDLE
}

/**
 * How much of the width each edge zone takes: 15% a side, 70% in the middle.
 * The gesture map is laid out from this same number, so the picture you are
 * shown once is the map you are actually using.
 */
const val SIDE_ZONE = 0.15f

private const val FLING_DOWN_PX_PER_S = 4_000f
