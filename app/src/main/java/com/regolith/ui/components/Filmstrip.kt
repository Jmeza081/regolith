package com.regolith.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Text
import com.regolith.ui.theme.RegolithTheme
import com.regolith.ui.theme.Spacing
import com.regolith.ui.theme.TextStyles
import com.regolith.ui.theme.ThumbShape
import com.regolith.ui.theme.designSp
import com.regolith.ui.util.formatClock
import kotlin.math.absoluteValue

/** One frame of a filmstrip: where it is in the clip, and the picture once it has arrived. */
data class StripFrame(val positionMs: Long, val bitmap: Bitmap?)

/**
 * A clip laid out as frames side by side; tapping one goes to that moment.
 * The scrub preview turned inside out: instead of one frame under a
 * dragging finger, the whole clip at once.
 *
 * Two places draw it. The flex-mode player puts a film's strip under the
 * hinge (16:9 frames with their times beneath), and the Shorts panel puts
 * a short's beside it (9:16 frames, a playhead, no times — its header
 * already says where you are).
 *
 * The frame whose slice holds [positionMs] is ringed. A frame that has not
 * arrived yet is a skeleton, and still a place you can tap to.
 *
 * @param frames in order; see [com.regolith.domain.playback.Filmstrip] for where they are taken.
 * @param testTag the strip's; each frame is `"${testTag}_<positionMs>"`.
 * @param aspectRatio each frame's shape, width over height.
 * @param showTimes the time under each frame.
 * @param playhead 0..1 through the clip, drawn as a 2dp accent line across
 *   the frames. A lambda read while DRAWING, so a playhead moving every
 *   frame redraws the strip without recomposing it. Null draws none (the
 *   flex deck has a scrubber of its own under the strip).
 */
@Composable
fun Filmstrip(
    frames: List<StripFrame>,
    positionMs: Long,
    onSeek: (positionMs: Long) -> Unit,
    testTag: String,
    modifier: Modifier = Modifier,
    aspectRatio: Float = 16f / 9f,
    showTimes: Boolean = true,
    spacing: Dp = Spacing.s8,
    playhead: (() -> Float)? = null,
) {
    val colors = RegolithTheme.colors
    val currentMs = frames.minByOrNull { (it.positionMs - positionMs).absoluteValue }?.positionMs
    Row(
        modifier
            .fillMaxWidth()
            .then(
                if (playhead == null) {
                    Modifier
                } else {
                    Modifier.drawWithContent {
                        drawContent()
                        // Past the frames' top and bottom edges by a little, so
                        // the line reads as a marker laid over the strip rather
                        // than a crack in one picture.
                        val overhang = 6.dp.toPx()
                        val line = 2.dp.toPx()
                        val x = playhead().coerceIn(0f, 1f) * size.width
                        drawRect(
                            color = colors.accent,
                            topLeft = Offset(x - line / 2, -overhang),
                            size = Size(line, size.height + 2 * overhang),
                        )
                    }
                },
            )
            .testTag(testTag),
        horizontalArrangement = Arrangement.spacedBy(spacing),
    ) {
        frames.forEach { frame ->
            FilmstripFrame(
                frame = frame,
                current = frame.positionMs == currentMs,
                onSeek = { onSeek(frame.positionMs) },
                aspectRatio = aspectRatio,
                showTime = showTimes,
                testTag = "${testTag}_${frame.positionMs}",
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** One frame of the strip: the picture once it lands, its time beneath when asked, ringed when the playhead is in its slice. */
@Composable
private fun FilmstripFrame(
    frame: StripFrame,
    current: Boolean,
    onSeek: () -> Unit,
    aspectRatio: Float,
    showTime: Boolean,
    testTag: String,
    modifier: Modifier = Modifier,
) {
    val colors = RegolithTheme.colors
    Column(
        modifier
            .clickable(
                interactionSource = null,
                indication = null,
                onClickLabel = "Go to ${formatClock(frame.positionMs)}",
                role = Role.Button,
                onClick = onSeek,
            )
            .testTag(testTag),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(aspectRatio)
                .then(if (current) Modifier.border(2.dp, colors.ink, ThumbShape) else Modifier)
                .padding(if (current) 3.dp else 0.dp)
                .clip(ThumbShape)
                .background(colors.skeleton),
        ) {
            frame.bitmap?.let { bmp ->
                Image(bitmap = bmp.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
        }
        if (showTime) {
            Text(
                formatClock(frame.positionMs),
                style = TextStyles.meta.copy(lineHeight = 11.designSp()),
                color = if (current) colors.ink else colors.metadata,
                maxLines = 1,
                overflow = TextOverflow.Clip,
                modifier = Modifier.padding(top = Spacing.s4),
            )
        }
    }
}
