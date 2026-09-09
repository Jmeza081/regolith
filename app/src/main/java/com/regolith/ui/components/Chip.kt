package com.regolith.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.regolith.R
import com.regolith.ui.theme.PillShape
import com.regolith.ui.theme.RegolithTheme
import com.regolith.ui.theme.Spacing
import com.regolith.ui.theme.TextStyles
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
            modifier = modifier.height(36.dp).background(colors.accent, PillShape).padding(horizontal = Spacing.s18),
        ) {
            Text(text, style = TextStyles.chipSelected, color = Color.White)
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
        Icon(painterResource(R.drawable.rg_ic_browse), contentDescription = null, tint = colors.ink, modifier = Modifier.size(10.dp))
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
