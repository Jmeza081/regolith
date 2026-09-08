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
import androidx.compose.ui.unit.dp
import com.regolith.domain.playback.AbLoop
import com.regolith.ui.theme.RegolithTheme

/**
 * The player's timeline (design section 10). Red fill to the playhead, a
 * lighter fill ahead of it for what has buffered off the share, an A–B
 * span drawn in white and flagged A and B, and chapter ticks.
 *
 * Drag anywhere on the track to scrub; [onScrub] fires with the live
 * fraction (Phase 3 shows a preview frame from it) and [onScrubEnd] with
 * the final one. Tap to jump.
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
            .height(40.dp)
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
        val trackH = 3.dp.toPx()
        val y = size.height / 2
        val w = size.width
        fun x(f: Float) = f * w
        fun xMs(ms: Long) = if (durationMs > 0) x((ms.toFloat() / durationMs).coerceIn(0f, 1f)) else 0f

        // track
        drawRoundRect(colors.ink.copy(alpha = 0.18f), Offset(0f, y - trackH / 2), Size(w, trackH), CornerRadius(trackH))
        // buffered
        if (buffered > shown) {
            drawRoundRect(colors.ink.copy(alpha = 0.32f), Offset(x(shown), y - trackH / 2), Size(x(buffered) - x(shown), trackH), CornerRadius(trackH))
        }
        // played
        drawRoundRect(colors.accent, Offset(0f, y - trackH / 2), Size(x(shown), trackH), CornerRadius(trackH))
        // chapters
        for (c in chaptersMs) {
            val cx = xMs(c)
            drawRect(colors.ground, Offset(cx - 1.dp.toPx(), y - trackH), Size(2.dp.toPx(), trackH * 2))
        }
        // A–B span in white, flagged
        val a = loop?.aMs ?: pendingAMs
        val b = loop?.bMs
        if (a != null) {
            val ax = xMs(a)
            if (b != null) {
                drawRoundRect(colors.ink, Offset(ax, y - trackH / 2), Size(xMs(b) - ax, trackH), CornerRadius(trackH))
                flag(this, xMs(b), y, "B", colors.ink, colors.ground)
            }
            flag(this, ax, y, "A", colors.ink, colors.ground)
        }
        // thumb
        val r = (if (dragging) 8.dp else 6.dp).toPx()
        drawCircle(colors.ink, r, Offset(x(shown), y))
        if (dragging) drawCircle(colors.ink.copy(alpha = 0.25f), r * 2, Offset(x(shown), y))
    }
}

private fun flag(scope: DrawScope, x: Float, y: Float, label: String, fill: Color, ink: Color) = with(scope) {
    val h = 14.dp.toPx()
    val w = 14.dp.toPx()
    val top = y - 8.dp.toPx() - h
    drawRoundRect(fill, Offset(x - w / 2, top), Size(w, h), CornerRadius(3.dp.toPx()))
    drawRect(fill, Offset(x - 1.dp.toPx() / 2, top + h), Size(1.dp.toPx(), 8.dp.toPx()))
    val paint = android.graphics.Paint().apply {
        color = ink.toArgb()
        textSize = 9.dp.toPx()
        textAlign = android.graphics.Paint.Align.CENTER
        isFakeBoldText = true
        isAntiAlias = true
    }
    drawContext.canvas.nativeCanvas.drawText(label, x, top + h - 3.5f.dp.toPx(), paint)
}
