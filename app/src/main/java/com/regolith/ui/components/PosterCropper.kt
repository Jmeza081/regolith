package com.regolith.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.regolith.domain.artwork.Dimensions
import com.regolith.domain.artwork.PosterFraming
import kotlin.math.roundToInt

/**
 * A fixed 2:3 crop box with the picture behind it: drag to move the picture,
 * pinch to zoom it. What sits inside the dashed box is exactly what
 * [PosterFraming.cropRect] will cut out, and the rest of the picture stays
 * visible, dimmed, so you can see what you are leaving out.
 *
 * The box never moves; the picture does. That is how phone photo croppers
 * behave, and it means the box is always as big as the space allows, which
 * is what makes small adjustments easy.
 *
 * Stateless, like a controlled `<input>`: [framing] comes in, every gesture
 * step goes out through [onFramingChange]. The screen keeps the value.
 *
 * @param image the full-resolution frame, or null while the first one loads
 *   (the box is drawn over black).
 * @param margin the space kept clear around the box, so its corners are
 *   never under a finger at the screen's edge.
 * @param contentPadding the part of the canvas that other chrome covers
 *   (frosted panels over a full-bleed picture). The box is fitted into what
 *   is left; the picture still draws, and still answers gestures, under the
 *   panels, so there is something behind the glass to blur.
 */
@Composable
fun PosterCropper(
    image: ImageBitmap?,
    framing: PosterFraming,
    onFramingChange: (PosterFraming) -> Unit,
    modifier: Modifier = Modifier,
    margin: androidx.compose.ui.unit.Dp = 16.dp,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    testTag: String = "poster_cropper",
) {
    val direction = LocalLayoutDirection.current
    val paddingNow by rememberUpdatedState(contentPadding)
    // Read inside the gesture without restarting it: a restarted
    // pointerInput would drop the finger mid-pinch.
    val framingNow by rememberUpdatedState(framing)
    val onChange by rememberUpdatedState(onFramingChange)
    val imageNow by rememberUpdatedState(image)

    Canvas(
        modifier
            .semantics { contentDescription = "Poster crop. Drag to move the picture, pinch to zoom." }
            .testTag(testTag)
            .pointerInput(Unit) {
                detectTransformGestures { centroid, pan, zoom, _ ->
                    val img = imageNow ?: return@detectTransformGestures
                    val area = areaFor(size.width.toFloat(), size.height.toFloat(), paddingNow, direction, this)
                    val box = boxFor(area.width, area.height, margin.toPx())
                    onChange(
                        framingNow.gesture(
                            img.dimensions, box,
                            centroidX = centroid.x - area.center.x, centroidY = centroid.y - area.center.y,
                            dx = pan.x, dy = pan.y, zoomBy = zoom,
                        ),
                    )
                }
            },
    ) {
        val area = areaFor(size.width, size.height, contentPadding, direction, this)
        val box = boxFor(area.width, area.height, margin.toPx())
        val boxTopLeft = Offset(area.center.x - box.width / 2f, area.center.y - box.height / 2f)
        image?.let { img ->
            val f = framing.clamped(img.dimensions, box)
            val s = f.scale(img.dimensions, box)
            val w = img.width * s
            val h = img.height * s
            drawImage(
                img,
                dstOffset = IntOffset((area.center.x + f.offsetX(box) - w / 2f).roundToInt(), (area.center.y + f.offsetY(box) - h / 2f).roundToInt()),
                dstSize = IntSize(w.roundToInt(), h.roundToInt()),
                filterQuality = FilterQuality.High,
            )
        }
        dimOutside(boxTopLeft, Size(box.width, box.height))
        drawBoxChrome(boxTopLeft, Size(box.width, box.height))
    }
}

private val ImageBitmap.dimensions get() = Dimensions(width.toFloat(), height.toFloat())

/** The canvas minus [padding], in pixels: where the box goes. */
private fun areaFor(width: Float, height: Float, padding: PaddingValues, direction: LayoutDirection, density: Density): Rect = with(density) {
    Rect(
        left = padding.calculateLeftPadding(direction).toPx(),
        top = padding.calculateTopPadding().toPx(),
        right = width - padding.calculateRightPadding(direction).toPx(),
        bottom = height - padding.calculateBottomPadding().toPx(),
    )
}

private fun boxFor(width: Float, height: Float, margin: Float): Dimensions =
    PosterFraming.boxIn((width - 2 * margin).coerceAtLeast(1f), (height - 2 * margin).coerceAtLeast(1f))

private val Scrim = Color(0x9E000000) // rgba(0,0,0,.62): what is left out still reads, but clearly out

/** Four bands around the box, rather than a path with a hole: nothing to allocate per frame. */
private fun DrawScope.dimOutside(topLeft: Offset, box: Size) {
    val right = topLeft.x + box.width
    val bottom = topLeft.y + box.height
    drawRect(Scrim, Offset.Zero, Size(size.width, topLeft.y))
    drawRect(Scrim, Offset(0f, bottom), Size(size.width, size.height - bottom))
    drawRect(Scrim, Offset(0f, topLeft.y), Size(topLeft.x, box.height))
    drawRect(Scrim, Offset(right, topLeft.y), Size(size.width - right, box.height))
}

/** The dashed edge, the thirds, and a solid bracket at each corner (the design's light-box). */
private fun DrawScope.drawBoxChrome(topLeft: Offset, box: Size) {
    val dash = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))
    drawRect(Color.White, topLeft, box, style = Stroke(width = 1.5.dp.toPx(), pathEffect = dash))
    val thirds = Color.White.copy(alpha = 0.35f)
    val thin = 1.dp.toPx()
    for (i in 1..2) {
        val x = topLeft.x + box.width * i / 3f
        val y = topLeft.y + box.height * i / 3f
        drawLine(thirds, Offset(x, topLeft.y), Offset(x, topLeft.y + box.height), thin, pathEffect = dash)
        drawLine(thirds, Offset(topLeft.x, y), Offset(topLeft.x + box.width, y), thin, pathEffect = dash)
    }
    val arm = 16.dp.toPx()
    val w = 3.dp.toPx()
    val l = topLeft.x
    val t = topLeft.y
    val r = topLeft.x + box.width
    val b = topLeft.y + box.height
    for ((corner, dir) in listOf(Offset(l, t) to Offset(1f, 1f), Offset(r, t) to Offset(-1f, 1f), Offset(l, b) to Offset(1f, -1f), Offset(r, b) to Offset(-1f, -1f))) {
        drawLine(Color.White, corner, corner + Offset(arm * dir.x, 0f), w)
        drawLine(Color.White, corner, corner + Offset(0f, arm * dir.y), w)
    }
}
