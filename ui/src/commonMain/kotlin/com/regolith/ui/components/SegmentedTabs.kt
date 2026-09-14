package com.regolith.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.regolith.ui.theme.PillShape
import com.regolith.ui.theme.RegolithTheme
import com.regolith.ui.theme.Spacing
import com.regolith.ui.theme.TextStyles
import com.regolith.ui.theme.scaledDp

/** One segment: a label, an optional count badge, and its testTag. */
data class Segment(val label: String, val testTag: String, val count: Int? = null)

/**
 * The tab switcher on Library and Search ("Network / On this device 3").
 * A #0F0F0F pill with a #1F1F1F hairline and 2dp inset; the selected
 * segment is a #1F1F1F pill 36dp tall with white 600 13px text, the
 * others #8A8A8A. Badges are #1A1A1A.
 */
@Composable
fun SegmentedTabs(
    segments: List<Segment>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RegolithTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(PillShape)
            .background(colors.surface)
            .border(1.dp, colors.hairline, PillShape)
            .padding(Spacing.s2),
        horizontalArrangement = Arrangement.spacedBy(Spacing.s2),
    ) {
        segments.forEachIndexed { index, segment ->
            val isSelected = index == selected
            val interaction = remember { MutableInteractionSource() }
            Row(
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .weight(1f)
                    .height(36.scaledDp())
                    .clip(PillShape)
                    .background(if (isSelected) colors.hairline else Color.Transparent)
                    .clickable(interactionSource = interaction, indication = null, role = Role.Tab, onClick = { onSelect(index) })
                    .semantics { this.selected = isSelected }
                    .testTag(segment.testTag),
            ) {
                Text(segment.label, style = TextStyles.buttonSmall, color = if (isSelected) colors.ink else colors.navIdle)
                if (segment.count != null) {
                    Spacer(Modifier.width(Spacing.s8))
                    CountBadge(segment.count)
                }
            }
        }
    }
}
