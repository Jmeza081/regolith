package com.regolith.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
 * The floating frosted nav pill over four destinations. Content runs under
 * it, so it never has a hard edge; the blur is what separates it from the
 * page. This is the ONLY blurred surface in the app.
 *
 * Drawn exactly once, by the nav graph, over the NavDisplay. Never inside a
 * screen: a per-screen pill cross-fades with the page on tab switch and the
 * one control meant to stay put is the one that visibly moves.
 *
 * @param hazeState the state the scrolling content is registered on with
 *   `Modifier.hazeSource(...)`; the pill samples it.
 * @param dimmed tabs to draw at reduced ink (e.g. Library and Browse when no
 *   source server exists: "dim in the pill rather than vanish, so the app
 *   never changes shape").
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
        backgroundColor = colors.surface,
        tints = listOf(HazeTint(colors.surface.copy(alpha = 0.72f))),
        blurRadius = 24.dp,
        noiseFactor = 0f,
    )
    Row(
        modifier = modifier
            .clip(PillShape)
            .hazeEffect(state = hazeState, style = style)
            .border(1.dp, colors.hairline, PillShape)
            .padding(horizontal = Spacing.s8, vertical = Spacing.s8)
            .testTag("nav_pill"),
        horizontalArrangement = Arrangement.spacedBy(Spacing.s8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MainTab.entries.forEach { tab ->
            val isSelected = tab == selected
            val ink = when {
                isSelected -> colors.ink
                tab in dimmed -> colors.hairline
                else -> colors.metadata
            }
            val interaction = remember { MutableInteractionSource() }
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .clip(PillShape)
                    .clickable(
                        interactionSource = interaction,
                        indication = null,
                        role = Role.Tab,
                        onClick = { onSelect(tab) },
                    )
                    .semantics { this.selected = isSelected }
                    .padding(horizontal = Spacing.s12, vertical = Spacing.s4)
                    .testTag(tab.testTag),
            ) {
                Icon(
                    painter = painterResource(tab.icon),
                    contentDescription = tab.label,
                    tint = ink,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(Modifier.height(Spacing.s2))
                Text(tab.label, style = TextStyles.chip, color = ink)
            }
        }
    }
}
