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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.regolith.domain.playback.AbLoop
import com.regolith.domain.playback.Chapter
import com.regolith.ui.theme.RegolithTheme

/**
 * The player's timeline (design section 10). A 22% white track, a 42%
 * white run for what has buffered off the share, the looped span at 80%
 * white flagged A and B in red, the red fill to the playhead, and in
 * landscape a 14dp red knob. Portrait draws the 3dp bar without a knob.
 *
 * Chapters cut the track into segments with a 2dp gap between them, the
 * way YouTube draws them, and the segment under the finger grows while
 * scrubbing so you can see which part you are in before the preview says
 * its name. Every layer — buffered, loop, fill — respects the gaps.
 *
 * Drag anywhere on the track to scrub; [onScrub] fires with the live
 * fraction (the preview frame follows it) and [onScrubEnd] with the final
 * one. Tap to jump. Nothing on it can be grabbed by mistake: chapter marks
 * are moved on the editor's own timeline, not here.
 *
 * [progress] and [buffered] are read once per frame *inside the draw block*,
 * not in composition. A moving playhead then repaints this Canvas and
 * nothing else — pass them from
 * [com.regolith.ui.player.SmoothProgress], which updates them about once per
 * pixel of travel, and tell it how wide the track turned out to be through
 * [onTrackWidth].
 */
@Composable
fun Scrubber(
    progress: () -> Float,
    durationMs: Long,
    buffered: () -> Float,
    onScrubStart: () -> Unit,
    onScrub: (fraction: Float) -> Unit,
    onScrubEnd: (fraction: Float) -> Unit,
    modifier: Modifier = Modifier,
    loop: AbLoop? = null,
    pendingAMs: Long? = null,
    chapters: List<Chapter> = emptyList(),
    trackHeight: Dp = 4.dp,
    showKnob: Boolean = true,
    onTrackWidth: (Int) -> Unit = {},
    testTag: String = "player_scrubber",
) {
    val colors = RegolithTheme.colors
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(36.dp)
            .onSizeChanged { onTrackWidth(it.width) }
            .semantics { progressBarRangeInfo = ProgressBarRangeInfo(if (dragging) dragFraction else progress(), 0f..1f) }
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
        // Both reads happen here, in the draw phase: a tick repaints the
        // track and skips composition and layout entirely.
        val shown = if (dragging) dragFraction else progress()
        val buffered = buffered()
        val trackH = trackHeight.toPx()
        val hotH = trackH * HOT_SCALE
        val y = size.height / 2
        val w = size.width
        fun x(f: Float) = f * w
        fun xMs(ms: Long) = if (durationMs > 0) x((ms.toFloat() / durationMs).coerceIn(0f, 1f)) else 0f

        // Segment edges in px: 0, every chapter start strictly inside the
        // film, w. A film with no chapters is one segment — the old bar.
        val edges = buildList {
            add(0f)
            if (durationMs > 0) chapters.map { it.startMs }.filter { it > 0 && it < durationMs }.sorted().forEach { add(xMs(it)) }
            add(w)
        }
        val gap = if (edges.size > 2) GAP.toPx() else 0f
        val hotX = if (dragging) x(shown) else -1f

        /** A run of colour from [fromX] to [toX], cut by the gaps and lifted where the finger is. */
        fun span(color: Color, fromX: Float, toX: Float) {
            for (i in 0 until edges.size - 1) {
                val segStart = if (i == 0) edges[i] else edges[i] + gap / 2
                val segEnd = if (i == edges.size - 2) edges[i + 1] else edges[i + 1] - gap / 2
                val start = maxOf(fromX, segStart)
                val end = minOf(toX, segEnd)
                if (end <= start) continue
                val h = if (hotX >= edges[i] && hotX < edges[i + 1]) hotH else trackH
                drawRoundRect(color, Offset(start, y - h / 2), Size(end - start, h), CornerRadius(h / 2))
            }
        }

        span(colors.trackWhite, 0f, w)
        if (buffered > 0f) span(Color(0x6BFFFFFF), 0f, x(buffered))
        val a = loop?.aMs ?: pendingAMs
        val b = loop?.bMs
        if (a != null && b != null) span(Color(0xCCFFFFFF), xMs(a), xMs(b))
        span(colors.accent, 0f, x(shown))
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

/** The 2dp cut between chapters. */
private val GAP = 2.dp

/** How much the finger's segment grows while scrubbing. */
private const val HOT_SCALE = 1.75f
