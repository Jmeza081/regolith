package com.regolith.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.regolith.ui.theme.PillShape
import com.regolith.ui.theme.RegolithTheme
import com.regolith.ui.theme.Spacing
import com.regolith.ui.theme.TextStyles
import com.regolith.ui.theme.scaledDp
import com.regolith.ui.theme.designSp

/** Where a chip sits decides how it is drawn (design section 01, "Badges & chips"). */
enum class ChipStyle {
    /** Filled black (72%) over poster art: "4K", "15m left". 600 10px, padding 2/8. */
    OverArt,
    /** Frosted on a surface: "HEVC", "8.4 GB". 600 11px tracked, padding 4/8. */
    OnSurface,
    /** Filled red, 36dp: only when selected: "1.0×". */
    Selected,
}

/**
 * A small pill carrying one fact. Pills carry every fact in this app; there
 * is no other badge shape.
 */
@Composable
fun Chip(
    text: String,
    style: ChipStyle,
    modifier: Modifier = Modifier,
    /** Over art, the res chip on a poster uses 4dp side padding instead of 8. */
    tight: Boolean = false,
) {
    val colors = RegolithTheme.colors
    when (style) {
        ChipStyle.OverArt -> Text(
            text, style = TextStyles.chipOverArt, color = colors.ink,
            modifier = modifier.background(colors.overArt, PillShape).padding(horizontal = if (tight) Spacing.s4 else Spacing.s8, vertical = Spacing.s2),
        )
        ChipStyle.OnSurface -> Text(
            text, style = TextStyles.chipOnSurface, color = colors.inkSoft,
            modifier = modifier.background(colors.frostBg, PillShape).border(1.dp, colors.frostBorder, PillShape).padding(horizontal = Spacing.s8, vertical = Spacing.s4),
        )
        ChipStyle.Selected -> Box(
            contentAlignment = Alignment.Center,
            modifier = modifier.height(36.scaledDp()).background(colors.accent, PillShape).padding(horizontal = Spacing.s18),
        ) {
            Text(text, style = TextStyles.chipSelected, color = Color.White)
        }
    }
}

/**
 * A chip you can turn on and off: Search's filters, and the points of
 * interest beside them. Red and white when on, frosted with a hairline
 * when off — the "Selected" style above, given an unselected state and a
 * tap. 34dp so a row of them clears the touch target minimum with the
 * row's own spacing.
 */
@Composable
fun FilterChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    testTag: String,
    modifier: Modifier = Modifier,
    /** A 14dp glyph before the label: the sliders on a chip that opens a sheet. */
    icon: Painter? = null,
) {
    val colors = RegolithTheme.colors
    Box(
        modifier
            .height(34.scaledDp())
            .clip(PillShape)
            .background(if (selected) colors.accent else colors.frostBg)
            .then(if (selected) Modifier else Modifier.border(1.dp, colors.frostBorder, PillShape))
            .clickable(interactionSource = null, indication = null, onClick = onClick)
            .padding(horizontal = Spacing.s12)
            .testTag(testTag),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(
                    icon, contentDescription = null,
                    tint = if (selected) Color.White else colors.inkSoft,
                    modifier = Modifier.size(14.scaledDp()),
                )
                Spacer(Modifier.width(Spacing.s4))
            }
            Text(
                text,
                style = if (selected) TextStyles.chipSelected.copy(fontSize = 12.designSp()) else TextStyles.buttonSmall.copy(fontSize = 12.designSp()),
                color = if (selected) Color.White else colors.inkSoft,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** The collection badge over a poster: a 10dp folder glyph and the count, black 72%. */
@Composable
fun CollectionBadge(count: Int, modifier: Modifier = Modifier) {
    val colors = RegolithTheme.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.background(colors.overArt, PillShape).padding(horizontal = Spacing.s4, vertical = Spacing.s2),
    ) {
        Icon(RegolithIcons.Browse, contentDescription = null, tint = colors.ink, modifier = Modifier.size(10.scaledDp()))
        Spacer(Modifier.width(Spacing.s4))
        Text(count.toString(), style = TextStyles.badge.copy(lineHeight = 12.designSp()), color = colors.ink)
    }
}

/**
 * The unwatched mark: a white dot with a halo "so it never reads as a
 * recording light". Over posters the halo is dark (2dp, black 45%); on
 * Home's bare posters it is light (3dp, white 22%).
 */
@Composable
fun UnwatchedDot(modifier: Modifier = Modifier, lightHalo: Boolean = false, size: androidx.compose.ui.unit.Dp = 7.dp) {
    val colors = RegolithTheme.colors
    val halo = if (lightHalo) 3.dp else 2.dp
    Box(
        modifier
            .size(size + halo * 2)
            .background(if (lightHalo) Color(0x38FFFFFF) else Color(0x73000000), PillShape),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(size).background(colors.ink, PillShape))
    }
}

/** Count badge inside a segmented tab ("On this device · 3"): #1A1A1A, 600 10px, padding 1/6. */
@Composable
fun CountBadge(count: Int, modifier: Modifier = Modifier) {
    val colors = RegolithTheme.colors
    Text(count.toString(), style = TextStyles.badge, color = colors.body, modifier = modifier.background(colors.badgeBg, PillShape).padding(horizontal = 6.dp, vertical = 1.dp))
}

/** "SHOWING" beside a server name: 600 9px tracked .1em, #1A1A1A pill. */
@Composable
fun Tag(text: String, modifier: Modifier = Modifier) {
    val colors = RegolithTheme.colors
    Text(text.uppercase(), style = TextStyles.tag, color = colors.body, modifier = modifier.background(colors.badgeBg, PillShape).padding(horizontal = 6.dp, vertical = 1.dp))
}

/** A 6dp status dot beside 12px #A0A0A0 text: "Connected · 2.4 TB free". */
@Composable
fun StatusLine(text: String, modifier: Modifier = Modifier, dotColor: Color = RegolithTheme.colors.ink) {
    val colors = RegolithTheme.colors
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(6.dp).background(dotColor, PillShape))
        Spacer(Modifier.width(Spacing.s8))
        Text(text, style = TextStyles.meta12, color = colors.body)
    }
}
