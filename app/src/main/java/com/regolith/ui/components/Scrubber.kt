package com.regolith.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.regolith.domain.playback.AbLoop
import com.regolith.ui.theme.RegolithTheme

/**
 * The player's timeline (design section 10). A 22% white track, a 42%
 * white run for what has buffered off the share, the looped span at 80%
 * white flagged A and B in red, the red fill to the playhead, and in
 * landscape a 14dp red knob. Portrait draws the 3dp bar without a knob.
 *
 * Drag anywhere on the track to scrub; [onScrub] fires with the live
 * fraction (the preview frame follows it) and [onScrubEnd] with the final
 * one. Tap to jump.
 */
@Composable
fun Scrubber(
    positionMs: Long,
    durationMs: Long,
    bufferedMs: Long,
    onScrubStart: () -> Unit,
    onScrub: (fraction: Float) -> Unit,
    onScrubEnd: (fraction: Float) -> Unit,
    modifier: Modifier = Modifier,
    loop: AbLoop? = null,
    pendingAMs: Long? = null,
    chaptersMs: List<Long> = emptyList(),
    trackHeight: Dp = 4.dp,
    showKnob: Boolean = true,
    testTag: String = "player_scrubber",
) {
    val colors = RegolithTheme.colors
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    val fraction = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    val buffered = if (durationMs > 0) (bufferedMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    val shown = if (dragging) dragFraction else fraction

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(36.dp)
            .semantics { progressBarRangeInfo = ProgressBarRangeInfo(shown, 0f..1f) }
            .testTag(testTag)
            .pointerInput(durationMs) {
                detectTapGestures { offset ->
                    val f = (offset.x / size.width).coerceIn(0f, 1f)
                    onScrubStart()
                    onScrubEnd(f)
                }
            }
            .pointerInput(durationMs) {
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        dragging = true
                        dragFraction = (offset.x / size.width).coerceIn(0f, 1f)
                        onScrubStart()
                        onScrub(dragFraction)
                    },
                    onHorizontalDrag = { change, _ ->
                        change.consume()
                        dragFraction = (change.position.x / size.width).coerceIn(0f, 1f)
                        onScrub(dragFraction)
                    },
                    onDragEnd = {
                        dragging = false
                        onScrubEnd(dragFraction)
                    },
                    onDragCancel = { dragging = false },
                )
            },
    ) {
        val trackH = trackHeight.toPx()
        val y = size.height / 2
        val w = size.width
        fun x(f: Float) = f * w
        fun xMs(ms: Long) = if (durationMs > 0) x((ms.toFloat() / durationMs).coerceIn(0f, 1f)) else 0f
        val radius = CornerRadius(trackH)

        drawRoundRect(colors.trackWhite, Offset(0f, y - trackH / 2), Size(w, trackH), radius)
        if (buffered > 0f) {
            drawRoundRect(Color(0x6BFFFFFF), Offset(0f, y - trackH / 2), Size(x(buffered), trackH), radius)
        }
        val a = loop?.aMs ?: pendingAMs
        val b = loop?.bMs
        if (a != null && b != null) {
            drawRoundRect(Color(0xCCFFFFFF), Offset(xMs(a), y - trackH / 2), Size(xMs(b) - xMs(a), trackH), radius)
        }
        drawRoundRect(colors.accent, Offset(0f, y - trackH / 2), Size(x(shown), trackH), radius)
        for (c in chaptersMs) {
            drawRect(colors.ground, Offset(xMs(c) - 1.dp.toPx(), y - trackH), Size(2.dp.toPx(), trackH * 2))
        }
        if (a != null) flag(this, xMs(a), y, "A", colors.accent)
        if (b != null) flag(this, xMs(b), y, "B", colors.accent)
        if (showKnob || dragging) {
            drawCircle(colors.accent, 7.dp.toPx(), Offset(x(shown), y))
        }
    }
}

/** The A / B flag: 700 9px white on a red 3dp-radius tag, 13dp above the track. */
private fun flag(scope: DrawScope, x: Float, y: Float, label: String, fill: Color) = with(scope) {
    val h = 13.dp.toPx()
    val w = 12.dp.toPx()
    val top = y - 13.dp.toPx() - h / 2
    drawRoundRect(fill, Offset(x - w / 2, top), Size(w, h), CornerRadius(3.dp.toPx()))
    val paint = android.graphics.Paint().apply {
        color = Color.White.toArgb()
        textSize = 9.dp.toPx()
        textAlign = android.graphics.Paint.Align.CENTER
        isFakeBoldText = true
        isAntiAlias = true
    }
    drawContext.canvas.nativeCanvas.drawText(label, x, top + h - 3.dp.toPx(), paint)
}
