package com.regolith.ui.lightbox

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.util.lerp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * How far one picture in the lightbox is zoomed and moved: a pinch or a
 * double tap on it, as a phone's gallery does. [scale] 1 is the picture
 * fitted to the screen; [offset] moves it, in the page's pixels, from the
 * centre. The picture's box keeps its place in the layout throughout —
 * the zoom is drawn, not laid out — so the flight back into its tile
 * starts from where the tile expects it.
 *
 * Web analogy: a CSS `transform: translate() scale()` on the image, driven
 * by pointer events, with the image's own box never moving.
 */
@Stable
internal class PictureZoom {
    var scale by mutableFloatStateOf(1f)
        private set
    var offset by mutableStateOf(Offset.Zero)
        private set

    /** The page's size, and the picture's fitted size in it at scale 1, in pixels. */
    var page: Size = Size.Zero
    var picture: Size = Size.Zero

    /** Zoomed in at all: the pager stops paging and a swipe moves the picture instead. */
    val zoomed: Boolean get() = scale > 1.01f

    /**
     * One step of a pinch: [zoomBy] around [centroid] (in the page), and
     * [pan]. The point under the fingers stays under them, the way a map
     * zooms: with k the change in scale, the new offset is
     * `(centroid − centre)(1 − k) + offset·k`.
     */
    fun transform(centroid: Offset, pan: Offset, zoomBy: Float) {
        val next = (scale * zoomBy).coerceIn(MIN_PINCH, MAX_SCALE)
        val k = next / scale
        val fromCentre = centroid - Offset(page.width / 2f, page.height / 2f)
        offset = fromCentre * (1f - k) + offset * k + pan
        scale = next
    }

    /**
     * One finger moving the zoomed picture by [pan], held at its edges. What
     * the edge held back is returned: a swipe that keeps pushing past the
     * side goes on to the next picture ([zoomGestures]).
     */
    fun pan(pan: Offset): Offset {
        val wanted = offset + pan
        val held = clamp(wanted, scale)
        offset = held
        return wanted - held
    }

    /** Back inside the limits once the fingers lift: no smaller than fitted, no further than the picture's edges. */
    suspend fun settle() {
        val target = scale.coerceIn(1f, MAX_SCALE)
        animateTo(target, if (target <= 1f) Offset.Zero else clamp(offset * (target / scale), target))
    }

    /** A double tap: in to [DOUBLE_TAP_SCALE] around [at], or back out to fitted. */
    suspend fun toggle(at: Offset) {
        if (zoomed) {
            animateTo(1f, Offset.Zero)
        } else {
            val fromCentre = at - Offset(page.width / 2f, page.height / 2f)
            animateTo(DOUBLE_TAP_SCALE, clamp(fromCentre * (1f - DOUBLE_TAP_SCALE), DOUBLE_TAP_SCALE))
        }
    }

    /** Fitted again at once: a picture swiped away, or closed. */
    fun reset() {
        scale = 1f
        offset = Offset.Zero
    }

    private suspend fun animateTo(toScale: Float, toOffset: Offset) {
        val fromScale = scale
        val fromOffset = offset
        animate(0f, 1f, animationSpec = tween(ZOOM_MS, easing = FastOutSlowInEasing)) { t, _ ->
            scale = lerp(fromScale, toScale, t)
            offset = lerp(fromOffset, toOffset, t)
        }
    }

    /** [wanted] held so the picture, at [atScale], never shows the page through one of its sides while it is bigger than the page. */
    private fun clamp(wanted: Offset, atScale: Float): Offset {
        val maxX = ((picture.width * atScale - page.width) / 2f).coerceAtLeast(0f)
        val maxY = ((picture.height * atScale - page.height) / 2f).coerceAtLeast(0f)
        return Offset(wanted.x.coerceIn(-maxX, maxX), wanted.y.coerceIn(-maxY, maxY))
    }

    companion object {
        const val MAX_SCALE = 5f
        const val DOUBLE_TAP_SCALE = 2.5f

        /** A pinch may go a little under fitted while the fingers are down, and springs back. */
        const val MIN_PINCH = 0.75f
        const val ZOOM_MS = 260
    }
}

/**
 * The lightbox's gestures on one picture, as a pointer handler (the
 * page's own, so they share its coordinates):
 * - two fingers zoom and move it ([PictureZoom.transform]), always;
 * - one finger moves it while it is zoomed, and is left alone while it is
 *   not, so the pager pages and a swipe down closes;
 * - pushed on past its side while zoomed, past [edgePx], a swipe goes to the
 *   neighbouring picture ([onEdge], forward to the next) rather than being
 *   lost against the edge.
 * A double tap and a plain tap are a separate handler (`detectTapGestures`),
 * which stands down as soon as this one has taken a gesture.
 */
internal suspend fun PointerInputScope.zoomGestures(zoom: PictureZoom, scope: CoroutineScope, edgePx: Float, onEdge: (forward: Boolean) -> Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        var transformed = false
        var pushedPast = 0f
        do {
            val event = awaitPointerEvent()
            val fingers = event.changes.count { it.pressed }
            if (fingers >= 2) {
                transformed = true
                zoom.transform(event.calculateCentroid(useCurrent = true), event.calculatePan(), event.calculateZoom())
                event.changes.forEach { it.consume() }
            } else if (fingers == 1 && zoom.zoomed) {
                transformed = true
                val held = zoom.pan(event.calculatePan())
                // Only a push that keeps going the same way counts toward the edge.
                pushedPast = if (held.x == 0f || (pushedPast != 0f && (held.x < 0) != (pushedPast < 0))) 0f else pushedPast + held.x
                event.changes.forEach { it.consume() }
            }
        } while (event.changes.any { it.pressed })
        if (transformed) scope.launch { zoom.settle() }
        if (abs(pushedPast) > edgePx) onEdge(pushedPast < 0)
    }
}
