package com.regolith.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.regolith.domain.playback.Story

/**
 * A story's place, across the top of its picture: one short bar per item,
 * full for those already shown and filling for the one showing, the way a
 * phone shows stories, so "how many more" never needs a number. Shared by
 * the Moments reel in the player and a collection's picture story.
 *
 * Past [max] items the bar shows only the [max] around the one showing
 * ([Story.window]) and slides along with it: a segment of a 300-picture
 * story would be a hair. [progress] is the showing item's own, read as the
 * bar is drawn, so a segment filling recomposes nothing.
 *
 * @param description what a screen reader hears: "Picture 4 of 12".
 */
@Composable
fun StorySegments(
    count: Int,
    index: Int,
    progress: () -> Float,
    description: String,
    testTag: String,
    modifier: Modifier = Modifier,
    max: Int = Story.MAX_SEGMENTS,
) {
    Canvas(
        modifier.fillMaxWidth().height(3.dp)
            .semantics { contentDescription = description }
            .testTag(testTag),
    ) {
        val shown = Story.window(count, index, max)
        val n = shown.last - shown.first + 1
        if (n <= 0) return@Canvas
        // The gaps narrow as a long story's bars do, so the bars stay bars.
        val gap = minOf(4.dp.toPx(), size.width / (n * 4f))
        val width = ((size.width - gap * (n - 1)) / n).coerceAtLeast(1f)
        val corner = CornerRadius(size.height / 2)
        for (i in shown) {
            val x = (i - shown.first) * (width + gap)
            drawRoundRect(if (i < index) Color.White else SEGMENT_TRACK, Offset(x, 0f), Size(width, size.height), corner)
            if (i == index) {
                val filled = progress().coerceIn(0f, 1f)
                if (filled > 0f) drawRoundRect(Color.White, Offset(x, 0f), Size(width * filled, size.height), corner)
            }
        }
    }
}

/** A segment still to come: the bar's track. */
private val SEGMENT_TRACK = Color.White.copy(alpha = 0.3f)
