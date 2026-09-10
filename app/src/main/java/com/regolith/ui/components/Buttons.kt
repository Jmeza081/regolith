package com.regolith.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.regolith.ui.theme.PillShape
import com.regolith.ui.theme.RegolithTheme
import com.regolith.ui.theme.Spacing
import com.regolith.ui.theme.TextStyles
import com.regolith.ui.theme.scaledDp

/*
 * Buttons (design section 01, "Buttons"), each a `font:`/`height:` pair
 * copied from the specimen. The heights below are the design's px; every
 * one is multiplied by SIZE_SCALE on the way to dp, so the pill keeps
 * the proportion to its label that the frames drew:
 *  - Primary      48dp · #E11B17 · 700 15px · optional 16dp leading icon, gap 8
 *  - Secondary    48dp · frosted (rgba(255,255,255,.06) + .18 hairline) · 600 15px, #EDEDED
 *  - Tertiary     44dp · no fill · 600 14px, white
 *  - Destructive  44dp · #E11B17 · 600 14px
 *  - Disabled     #161616 fill, #4A4A4A ink
 *  - Compact      42dp · 600 13px (Settings' "Scan all" / "Disconnect")
 *  - Icon         48dp circle, frosted, 18dp glyph
 * Red fills the ONE action a screen wants; never a second red fill.
 *
 * Every button takes a `testTag` so argent/uiautomator can find it by a
 * stable id (like `data-testid`). Convention: `feature_element`.
 */

@Composable
private fun BasePill(
    text: String,
    onClick: () -> Unit,
    testTag: String,
    modifier: Modifier,
    enabled: Boolean,
    height: Dp,
    background: Color,
    border: Color?,
    ink: Color,
    style: TextStyle,
    leadingIcon: Painter?,
    horizontalPadding: Dp = Spacing.s18,
) {
    val colors = RegolithTheme.colors
    val bg = if (enabled) background else colors.disabledBg
    val fg = if (enabled) ink else colors.disabledInk
    Row(
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .height(height)
            .clip(PillShape)
            .background(bg)
            .then(if (border != null && enabled) Modifier.border(1.dp, border, PillShape) else Modifier)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = horizontalPadding)
            .testTag(testTag),
    ) {
        if (leadingIcon != null) {
            Icon(leadingIcon, contentDescription = null, tint = fg, modifier = Modifier.size(16.scaledDp()))
            Box(Modifier.size(Spacing.s8))
        }
        Text(text, style = style, color = fg, maxLines = 1)
    }
}

/** Red fill. One per screen. */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    testTag: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: Painter? = null,
    /** 42dp, 600 13px: the Settings row buttons. */
    compact: Boolean = false,
) {
    val colors = RegolithTheme.colors
    BasePill(
        text, onClick, testTag, modifier, enabled,
        height = if (compact) 42.scaledDp() else 48.scaledDp(), background = colors.accent, border = null, ink = Color.White,
        style = if (compact) TextStyles.buttonSmall else TextStyles.buttonPrimary, leadingIcon = leadingIcon,
    )
}

/** Frosted pill: `rgba(255,255,255,.06)` fill, `.18` hairline, #EDEDED ink. */
@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    testTag: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: Painter? = null,
    compact: Boolean = false,
) {
    val colors = RegolithTheme.colors
    BasePill(
        text, onClick, testTag, modifier, enabled,
        height = if (compact) 42.scaledDp() else 48.scaledDp(), background = colors.frostBg, border = colors.frostBorder, ink = colors.inkSoft,
        style = if (compact) TextStyles.buttonSmall else TextStyles.buttonSecondary, leadingIcon = leadingIcon,
    )
}

/** Plain white text, no fill, no underline. 44dp. */
@Composable
fun TertiaryButton(
    text: String,
    onClick: () -> Unit,
    testTag: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = RegolithTheme.colors
    BasePill(
        text, onClick, testTag, modifier, enabled,
        height = 44.scaledDp(), background = Color.Transparent, border = null, ink = colors.ink,
        style = TextStyles.buttonTertiary, leadingIcon = null,
    )
}

/**
 * Destructive: the same red, 44dp, 600 14px ("Disconnect"). The design
 * accepts that red reads as both "on" and "destructive"; keep it rare.
 */
@Composable
fun DestructiveButton(
    text: String,
    onClick: () -> Unit,
    testTag: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    compact: Boolean = false,
) {
    val colors = RegolithTheme.colors
    BasePill(
        text, onClick, testTag, modifier, enabled,
        height = if (compact) 42.scaledDp() else 44.scaledDp(), background = colors.accent, border = null, ink = Color.White,
        style = if (compact) TextStyles.buttonSmall else TextStyles.buttonTertiary, leadingIcon = null,
    )
}

/** 48dp frosted circle with an 18dp glyph (Title Detail's secondary action). */
@Composable
fun IconCircleButton(
    icon: Painter,
    contentDescription: String,
    onClick: () -> Unit,
    testTag: String,
    modifier: Modifier = Modifier,
    /** Over the picture: `rgba(0,0,0,.42)` fill and a `.34` hairline instead of the frost. */
    onMedia: Boolean = false,
    size: Dp = 48.scaledDp(),
    iconSize: Dp = 18.scaledDp(),
) {
    val colors = RegolithTheme.colors
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(size)
            .clip(PillShape)
            .background(if (onMedia) colors.onMediaCircleBg else colors.frostBg)
            .border(1.dp, if (onMedia) colors.onMediaCircleBorder else colors.frostBorder, PillShape)
            .clickable(role = Role.Button, onClick = onClick)
            .testTag(testTag),
    ) {
        Icon(icon, contentDescription = contentDescription, tint = if (onMedia) colors.ink else colors.inkSoft, modifier = Modifier.size(iconSize))
    }
}
