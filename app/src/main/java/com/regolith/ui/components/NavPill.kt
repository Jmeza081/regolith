package com.regolith.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.regolith.ui.navigation.MainTab
import com.regolith.ui.theme.PillShape
import com.regolith.ui.theme.RegolithTheme
import com.regolith.ui.theme.Spacing
import com.regolith.ui.theme.TextStyles
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import com.regolith.ui.theme.scaledDp

/**
 * The floating frosted nav pill (design: every tab frame). 62dp tall,
 * 18dp from each side and 26dp from the bottom, `rgba(0,0,0,.52)` under
 * a 20dp blur with a `rgba(255,255,255,.16)` hairline and a soft drop
 * shadow. Four equal cells: a 19dp glyph over a 10px tracked uppercase
 * label, white when selected, #8A8A8A otherwise, and 22% white when
 * [dimmed] (a tab with nothing behind it yet).
 *
 * [vertical] turns the same pill on its side for a wide window (a
 * foldable's inner display, a tablet): a [NAV_RAIL_WIDTH] column of the
 * same four cells hugging the start edge, where the thumbs rest on a
 * book-sized device. Same blur, same tokens, same test tags; only the
 * axis changes, which is why it is a flag and not a second composable.
 *
 * Drawn exactly once, by the nav graph, over the NavDisplay. This is the
 * ONLY blurred surface in the app.
 */
@Composable
fun NavPill(
    selected: MainTab,
    onSelect: (MainTab) -> Unit,
    hazeState: HazeState,
    modifier: Modifier = Modifier,
    dimmed: Set<MainTab> = emptySet(),
    vertical: Boolean = false,
) {
    val colors = RegolithTheme.colors
    val style = HazeStyle(
        backgroundColor = colors.ground,
        tints = listOf(HazeTint(colors.pillBg)),
        blurRadius = 20.dp,
        noiseFactor = 0f,
    )
    val frosted = Modifier
        .shadow(elevation = 12.dp, shape = PillShape, ambientColor = colors.ground, spotColor = colors.ground)
        .clip(PillShape)
        .hazeEffect(state = hazeState, style = style)
        .background(colors.pillBg)
        .border(1.dp, colors.pillBorder, PillShape)
    val cell: @Composable (MainTab, Modifier) -> Unit = { tab, cellModifier ->
        NavCell(tab, selected = tab == selected, dimmed = tab in dimmed, onSelect = onSelect, modifier = cellModifier)
    }
    if (vertical) {
        // The rail: one cell high per tab, 10dp of inner padding top and
        // bottom so the end labels clear the 50% radius, as on the pill.
        Column(
            modifier = modifier
                .width(NAV_RAIL_WIDTH)
                .then(frosted)
                .padding(vertical = 10.dp)
                .testTag("nav_pill"),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            MainTab.entries.forEach { tab -> cell(tab, Modifier.fillMaxWidth().height(NAV_RAIL_CELL_HEIGHT)) }
        }
        return
    }
    Row(
        modifier = modifier
            .padding(horizontal = Spacing.s18)
            // Capped rather than full-width. The pill was 89% of a 320px
            // frame and 91% of the phone -- proportionally right, but four
            // weighted slots across 375dp put 94dp around labels that need
            // ~67dp, and the gaps read as slack. This is a judgement call
            // against the frame, not a correction of it.
            .widthIn(max = NAV_PILL_MAX_WIDTH)
            .height(62.dp)
            .then(frosted)
            // 10dp, not 4: the end labels sit against a 50% radius, and a
            // 4dp inset put SETTINGS right on the curve once the pill stopped
            // stretching.
            .padding(horizontal = 10.dp)
            .testTag("nav_pill"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MainTab.entries.forEach { tab -> cell(tab, Modifier.weight(1f).fillMaxHeight()) }
    }
}

/** One tab cell: glyph over label, the whole cell tappable. Shared by the pill and the rail. */
@Composable
private fun NavCell(tab: MainTab, selected: Boolean, dimmed: Boolean, onSelect: (MainTab) -> Unit, modifier: Modifier) {
    val colors = RegolithTheme.colors
    val ink = when {
        selected -> colors.ink
        dimmed -> colors.navDimmed
        else -> colors.navIdle
    }
    val interaction = remember { MutableInteractionSource() }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = modifier
            .clickable(interactionSource = interaction, indication = null, role = Role.Tab, onClick = { onSelect(tab) })
            .semantics { this.selected = selected }
            .testTag(tab.testTag),
    ) {
        Icon(painter = painterResource(tab.icon), contentDescription = tab.label, tint = ink, modifier = Modifier.size(19.scaledDp()))
        Spacer(Modifier.height(Spacing.s4))
        Text(tab.label.uppercase(), style = TextStyles.navLabel, color = ink)
    }
}

/** The rail's width on a wide window: the widest label ("SETTINGS") plus the pill's own 10dp each side. */
val NAV_RAIL_WIDTH: Dp = 84.dp

/** One rail cell: the pill's 62dp plus the room a stacked cell needs between glyph and label. */
private val NAV_RAIL_CELL_HEIGHT: Dp = 66.dp

/**
 * What a tab screen leaves free on the start edge when the pill is a rail:
 * the s18 gutter the rail floats in, plus the rail. The screen's own s18
 * gutter then puts content 120dp in, which is where the design's inner-
 * display frames put it.
 */
val NAV_RAIL_INSET: Dp = Spacing.s18 + NAV_RAIL_WIDTH

/** Four 81dp slots plus 10dp of inner padding each side. */
private val NAV_PILL_MAX_WIDTH = 344.dp

/**
 * How far a scrolling tab screen must pad its content so the last row can
 * clear the pill: 62dp of pill, 26dp under it, 8dp over it, and the s18
 * gutter. The one number every tab screen used to hard-code.
 */
val NAV_PILL_CLEARANCE: Dp = 112.dp

/**
 * The edge the nav pill occupies, as padding a screen adds on top of its own
 * gutters. On a phone the pill floats at the bottom, so this is
 * `PaddingValues(bottom = NAV_PILL_CLEARANCE)`; on a wide window (a
 * foldable's inner display, a tablet) the pill becomes a rail on the start
 * edge and the padding moves with it. Provided once by the nav graph; the
 * default is the phone value so previews and tests need nothing.
 *
 * Web analogy: a CSS custom property for the nav's safe area, set on the
 * root and read by every page.
 */
val LocalNavPillInsets = staticCompositionLocalOf { PaddingValues(bottom = NAV_PILL_CLEARANCE) }
