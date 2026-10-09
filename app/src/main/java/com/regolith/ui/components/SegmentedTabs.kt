package com.regolith.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.IntrinsicMeasurable
import androidx.compose.ui.layout.IntrinsicMeasureScope
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
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
 *
 * Segments share the width equally while every label and count fits that
 * way. Where they would not (a collection's tabs in the wall beside a
 * title's page on the inner display), each segment takes what its label
 * and count need and shares what is left; tighter still, the counts give
 * way and the labels stay whole ([segmentWidths]).
 */
@Composable
fun SegmentedTabs(
    segments: List<Segment>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RegolithTheme.colors
    Layout(
        modifier = modifier
            .fillMaxWidth()
            .clip(PillShape)
            .background(colors.surface)
            .border(1.dp, colors.hairline, PillShape)
            .padding(Spacing.s2),
        content = {
            segments.forEachIndexed { index, segment ->
                val isSelected = index == selected
                val interaction = remember { MutableInteractionSource() }
                Row(
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .height(36.scaledDp())
                        .clip(PillShape)
                        .background(if (isSelected) colors.hairline else Color.Transparent)
                        .clickable(interactionSource = interaction, indication = null, role = Role.Tab, onClick = { onSelect(index) })
                        .semantics { this.selected = isSelected }
                        .testTag(segment.testTag),
                ) {
                    SegmentLabel(segment.label, segment.count, if (isSelected) colors.ink else colors.navIdle)
                }
            }
        },
    ) { measurables, constraints ->
        val gap = Spacing.s2.roundToPx()
        val total = (constraints.maxWidth - gap * (measurables.size - 1)).coerceAtLeast(0)
        // Each segment's needs, asked before it is measured: its label and
        // count (max), and its label alone (min, see SegmentLabelPolicy).
        val widths = segmentWidths(
            total,
            full = measurables.map { it.maxIntrinsicWidth(Constraints.Infinity) },
            bare = measurables.map { it.minIntrinsicWidth(Constraints.Infinity) },
        )
        val placeables = measurables.mapIndexed { i, m -> m.measure(Constraints.fixedWidth(widths[i]).copy(maxHeight = constraints.maxHeight)) }
        val height = placeables.maxOfOrNull { it.height } ?: 0
        layout(constraints.maxWidth, height) {
            var x = 0
            placeables.forEach { p ->
                p.placeRelative(x, (height - p.height) / 2)
                x += p.width + gap
            }
        }
    }
}

/**
 * How wide each segment is, from the [total] there is and what each needs
 * with its count ([full]) and without ([bare]): equal shares while every
 * segment fits one; otherwise each its full need plus an equal share of
 * what is left; failing that its bare need, so the counts give way; and
 * failing even that, [bare] scaled down to fit, the labels ellipsized.
 * The widths always add up to [total].
 */
internal fun segmentWidths(total: Int, full: List<Int>, bare: List<Int>): List<Int> {
    val n = full.size
    if (n == 0) return emptyList()
    fun share(needs: List<Int>): List<Int> = needs.map { it + (total - needs.sum()) / n }
    val widths = when {
        full.all { it <= total / n } -> List(n) { total / n }
        full.sum() <= total -> share(full)
        bare.sum() <= total -> share(bare)
        else -> bare.sum().coerceAtLeast(1).let { sum -> bare.map { (it.toLong() * total / sum).toInt() } }
    }.toMutableList()
    // Rounding leaves a pixel or two: the last segment takes them, so the row ends where the pill does.
    widths[n - 1] += total - widths.sum()
    return widths
}

/**
 * A segment's label and its count, centred. Where both do not fit the
 * segment's width, the count gives way and the label stays whole; a label
 * too long even alone ends in an ellipsis rather than breaking onto a
 * second line the pill has no room for.
 */
@Composable
private fun SegmentLabel(label: String, count: Int?, color: Color) {
    Layout(
        content = {
            Text(label, style = TextStyles.buttonSmall, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (count != null) CountBadge(count)
        },
        measurePolicy = SegmentLabelPolicy,
    )
}

/**
 * Lays out [SegmentLabel], and answers [SegmentedTabs]' question of what a
 * segment needs: the label and its count at most, the label alone at least.
 */
private object SegmentLabelPolicy : MeasurePolicy {
    override fun MeasureScope.measure(measurables: List<Measurable>, constraints: Constraints): MeasureResult {
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val gap = Spacing.s8.roundToPx()
        val badge = measurables.getOrNull(1)?.measure(loose)
        val labelWidth = measurables[0].maxIntrinsicWidth(loose.maxHeight)
        val withBadge = badge != null && (!constraints.hasBoundedWidth || labelWidth + gap + badge.width <= constraints.maxWidth)
        val text = measurables[0].measure(if (withBadge) loose.copy(maxWidth = (loose.maxWidth - gap - badge!!.width).coerceAtLeast(0)) else loose)
        val shown = if (withBadge) text.width + gap + badge!!.width else text.width
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else shown
        val height = maxOf(text.height, if (withBadge) badge!!.height else 0)
        return layout(width, height) {
            val x = (width - shown) / 2
            text.placeRelative(x, (height - text.height) / 2)
            if (withBadge) badge!!.placeRelative(x + text.width + gap, (height - badge.height) / 2)
        }
    }

    override fun IntrinsicMeasureScope.minIntrinsicWidth(measurables: List<IntrinsicMeasurable>, height: Int): Int =
        measurables[0].maxIntrinsicWidth(height)

    override fun IntrinsicMeasureScope.maxIntrinsicWidth(measurables: List<IntrinsicMeasurable>, height: Int): Int {
        val label = measurables[0].maxIntrinsicWidth(height)
        val badge = measurables.getOrNull(1)?.maxIntrinsicWidth(height) ?: return label
        return label + Spacing.s8.roundToPx() + badge
    }

    override fun IntrinsicMeasureScope.minIntrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Int): Int =
        measurables.maxOf { it.minIntrinsicHeight(width) }

    override fun IntrinsicMeasureScope.maxIntrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Int): Int =
        measurables.maxOf { it.maxIntrinsicHeight(width) }
}
