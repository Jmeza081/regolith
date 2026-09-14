package com.regolith.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.dp
import com.regolith.ui.theme.PillShape
import com.regolith.ui.theme.RegolithTheme
import com.regolith.ui.theme.Spacing
import com.regolith.ui.theme.TextStyles
import com.regolith.ui.theme.scaledDp

/**
 * A tappable pill: "1.0×", "A–B", "Chapters" (design section 10), or a
 * glyph on its own when [text] is blank — then pass a [contentDescription].
 * Over the picture it is 34dp with a 22% white hairline on 35% black;
 * off the picture (portrait, under the video) it is the 40dp frosted
 * pill. [selected] fills it red, the one place a pill may carry the
 * accent. Optional leading glyph.
 */
@Composable
fun PillButton(
    text: String,
    onClick: () -> Unit,
    testTag: String,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    icon: Painter? = null,
    onLongClick: (() -> Unit)? = null,
    onMedia: Boolean = true,
    /** Swaps the icon for a spinner and dims the label: the pill is there, its content is not ready. */
    loading: Boolean = false,
    /**
     * What a screen reader says. Required when [text] is blank — a glyph-only
     * pill has nothing for it to read otherwise.
     */
    contentDescription: String? = null,
    /**
     * 0..1 draws a determinate ring in place of the glyph: the pill is
     * reporting a job with a known length (a download). Wins over [loading].
     */
    progress: Float? = null,
) {
    val colors = RegolithTheme.colors
    val background = when {
        selected -> colors.accent
        onMedia -> colors.onMediaBg
        else -> colors.frostBg
    }
    val border = when {
        selected -> Color.Transparent
        onMedia -> colors.onMediaBorder
        else -> colors.frostBorder
    }
    val ink = if (loading) colors.metadata else if (selected || onMedia) colors.ink else colors.inkSoft
    val height = if (onMedia) 34.scaledDp() else 40.scaledDp()
    // A glyph on its own is a CIRCLE: as wide as it is tall, the glyph in the
    // middle, no side padding. With the text padding kept it came out 38 wide
    // by 34 tall — a pill with nothing in the gap, which read as a mistake.
    val circle = text.isEmpty()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (circle) Arrangement.Center else Arrangement.Start,
        modifier = modifier
            .height(height)
            .then(if (circle) Modifier.width(height) else Modifier)
            .clip(PillShape)
            .background(background)
            .border(1.dp, border, PillShape)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .then(if (contentDescription != null) Modifier.semantics { this.contentDescription = contentDescription } else Modifier)
            .then(if (circle) Modifier else Modifier.padding(horizontal = Spacing.s12))
            .testTag(testTag),
    ) {
        val glyph = if (circle) 16.dp else 14.dp
        when {
            progress != null -> CircularProgressIndicator(
                progress = { progress.coerceIn(0f, 1f) }, color = ink, trackColor = colors.hairline,
                strokeWidth = 1.5.dp, gapSize = 0.dp, modifier = Modifier.size(glyph),
            )
            loading -> CircularProgressIndicator(color = ink, strokeWidth = 1.5.dp, modifier = Modifier.size(13.dp))
            icon != null -> Icon(icon, contentDescription = null, tint = ink, modifier = Modifier.size(glyph))
        }
        if (!circle) {
            if (loading || icon != null || progress != null) Spacer(Modifier.width(Spacing.s8))
            Text(text, style = TextStyles.buttonSmall, color = ink)
        }
    }
}
