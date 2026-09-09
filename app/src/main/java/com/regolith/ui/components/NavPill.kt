package com.regolith.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
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

/**
 * The floating frosted nav pill (design: every tab frame). 62dp tall,
 * 18dp from each side and 26dp from the bottom, `rgba(0,0,0,.52)` under
 * a 20dp blur with a `rgba(255,255,255,.16)` hairline and a soft drop
 * shadow. Four equal cells: a 19dp glyph over a 10px tracked uppercase
 * label, white when selected, #8A8A8A otherwise, and 22% white when
 * [dimmed] (a tab with nothing behind it yet).
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
) {
    val colors = RegolithTheme.colors
    val style = HazeStyle(
        backgroundColor = colors.ground,
        tints = listOf(HazeTint(colors.pillBg)),
        blurRadius = 20.dp,
        noiseFactor = 0f,
    )
    Row(
        modifier = modifier
            .padding(horizontal = Spacing.s18)
            .height(62.dp)
            .shadow(elevation = 12.dp, shape = PillShape, ambientColor = colors.ground, spotColor = colors.ground)
            .clip(PillShape)
            .hazeEffect(state = hazeState, style = style)
            .background(colors.pillBg)
            .border(1.dp, colors.pillBorder, PillShape)
            .padding(horizontal = Spacing.s4)
            .testTag("nav_pill"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MainTab.entries.forEach { tab ->
            val isSelected = tab == selected
            val ink = when {
                isSelected -> colors.ink
                tab in dimmed -> colors.navDimmed
                else -> colors.navIdle
            }
            val interaction = remember { MutableInteractionSource() }
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clickable(interactionSource = interaction, indication = null, role = Role.Tab, onClick = { onSelect(tab) })
                    .semantics { this.selected = isSelected }
                    .testTag(tab.testTag),
            ) {
                Icon(painter = painterResource(tab.icon), contentDescription = tab.label, tint = ink, modifier = Modifier.size(19.dp))
                Spacer(Modifier.height(Spacing.s4))
                Text(tab.label.uppercase(), style = TextStyles.navLabel, color = ink)
            }
        }
    }
}
