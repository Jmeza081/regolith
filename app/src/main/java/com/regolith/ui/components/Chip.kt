package com.regolith.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.regolith.ui.theme.PillShape
import com.regolith.ui.theme.RegolithTheme
import com.regolith.ui.theme.Spacing
import com.regolith.ui.theme.TextStyles

/** Where a chip sits decides how it is drawn (design section 01, "Badges & chips"). */
enum class ChipStyle {
    /** Filled black, over poster art: "4K", "1h 56m". */
    OverArt,
    /** Outlined on a surface: "HEVC", "8.4 GB". */
    OnSurface,
    /** Filled red. Only when selected: "1.0×". */
    Selected,
}

/**
 * A small pill carrying one fact. Pills carry every fact in this app; there
 * is no other badge shape. Text only; icon chips are added when a screen
 * needs one, not before.
 */
@Composable
fun Chip(
    text: String,
    style: ChipStyle,
    modifier: Modifier = Modifier,
) {
    val colors = RegolithTheme.colors
    val (background, border, ink) = when (style) {
        ChipStyle.OverArt -> Triple(colors.ground.copy(alpha = 0.78f), Color.Transparent, colors.ink)
        ChipStyle.OnSurface -> Triple(Color.Transparent, colors.raised, colors.body)
        ChipStyle.Selected -> Triple(colors.accent, Color.Transparent, colors.inkSoft)
    }
    Text(
        text = text,
        style = TextStyles.chip,
        color = ink,
        modifier = modifier
            .background(background, PillShape)
            .border(1.dp, border, PillShape)
            .padding(horizontal = Spacing.s8, vertical = Spacing.s2),
    )
}
