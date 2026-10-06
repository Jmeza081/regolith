package com.regolith.ui.library

import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.regolith.ui.adaptive.LocalWindowShape
import com.regolith.ui.components.LocalNavRailInset
import com.regolith.ui.theme.Spacing
import kotlin.math.roundToInt

/**
 * How many tiles across a wall gets: the Network wall and the On this device
 * grid alike, so the two tabs stay the same kind of page at the same size.
 *
 * - **A phone:** three, always (the Fold's cover screen included).
 * - **A wide window, with the wall given the whole screen** ([fullWidth],
 *   beside the rail): [perRow], the owner's Settings › Display › Posters per
 *   row. If that would make a poster narrower than [WIDE_TILE_MIN], it is as
 *   many as fit at that width instead.
 * - **Narrower than that** (a title's page open beside the wall): the
 *   posters keep the size they have at full width, as nearly as whole
 *   columns allow, and the COLUMNS go instead. The wall makes room rather
 *   than shrinking the tiles you were looking at.
 *
 * Never fewer than two: one column beside a page reads as a list that lost
 * its layout.
 */
internal fun wallColumns(width: Dp, wide: Boolean, perRow: Int, fullWidth: Dp): Int {
    if (!wide) return PHONE_COLUMNS
    fun fitting(w: Dp): Int = ((w + WALL_GAP) / (WIDE_TILE_MIN + WALL_GAP)).toInt()
    val across = perRow.coerceAtMost(fitting(fullWidth)).coerceAtLeast(2)
    // A poster's width with the whole screen: the size the wall keeps.
    val poster = (fullWidth + WALL_GAP) / across - WALL_GAP
    val nearest = ((width + WALL_GAP) / (poster + WALL_GAP)).roundToInt()
    return nearest.coerceAtMost(fitting(width)).coerceAtLeast(2)
}

/**
 * The width a wall's tiles would share with the whole window to itself: the
 * window, less the rail beside it and the wall's own side padding (s18 each
 * side, as both walls lay out). Taken from the window rather than the wall's
 * own box because, with a title's page open, that box is half of it.
 */
@Composable
internal fun wallFullWidth(): Dp = LocalWindowShape.current.width - LocalNavRailInset.current - Spacing.s18 * 2

/** [wallColumns] as a lazy grid's column rule, the cells sharing the width evenly as [GridCells.Fixed] does. */
internal class WallCells(private val wide: Boolean, private val perRow: Int, private val fullWidth: Dp) : GridCells {
    override fun Density.calculateCrossAxisCellSizes(availableSize: Int, spacing: Int): List<Int> {
        val count = wallColumns(availableSize.toDp(), wide, perRow, fullWidth)
        val usable = availableSize - spacing * (count - 1)
        return List(count) { usable / count + if (it < usable % count) 1 else 0 }
    }

    override fun equals(other: Any?): Boolean =
        other is WallCells && other.wide == wide && other.perRow == perRow && other.fullWidth == fullWidth

    override fun hashCode(): Int = (wide.hashCode() * 31 + perRow) * 31 + fullWidth.hashCode()
}

/** Tiles across a wall on a phone ([wallColumns]): the design's three. */
internal const val PHONE_COLUMNS = 3

/**
 * The narrowest a tile gets on a wide window ([wallColumns]): about the
 * phone's own (three across a 411dp screen are 120dp; the Fold's cover screen
 * makes them ~106). Below it, a wall shows fewer posters than asked for.
 */
internal val WIDE_TILE_MIN = 104.dp

/** The gap between tiles, as both walls space them (Spacing.s8). */
private val WALL_GAP = Spacing.s8
