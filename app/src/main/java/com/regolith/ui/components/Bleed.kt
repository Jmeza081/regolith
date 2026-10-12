package com.regolith.ui.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import kotlin.math.roundToInt

/**
 * Lays this out [amount] wider on each side than its parent allows, out past
 * the parent's horizontal padding, without the parent making room for it.
 * Web analogy: CSS's negative side margins.
 *
 * For a background that should not stop where the content does: a
 * collection profile's light, which runs to the window's edges under a lazy
 * grid's content padding, and the reel's playing row, whose highlight
 * reaches past its column while the row pads its contents back in line with
 * everything else in the column. The height is the content's own, or fixed
 * where the parent fixes it.
 */
fun Modifier.bleedHorizontally(amount: Dp): Modifier = layout { measurable, constraints ->
    if (!constraints.hasBoundedWidth) {
        // Nothing to reach past in a row that scrolls sideways.
        val placeable = measurable.measure(constraints)
        return@layout layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    }
    val extra = amount.roundToPx()
    val width = constraints.maxWidth + extra * 2
    val placeable = measurable.measure(constraints.copy(minWidth = width, maxWidth = width))
    layout(constraints.maxWidth, placeable.height) { placeable.place(-extra, 0) }
}

/**
 * Lays this out [by] wider than it is offered, reaching past its END edge
 * into the padding there, while telling its parent it is exactly the width
 * it was given, so nothing around it moves. Placed with `placeRelative`, so
 * in a right-to-left layout it reaches left, which is the end there.
 *
 * For what should keep its place when a list makes room at its end edge for
 * an [AlphabetRail]: the move sheet's rail reaching into the sheet's gutter,
 * and a collection profile's header and tabs, which stay centred while the
 * wall under them makes room.
 */
fun Modifier.bleedEnd(by: Dp): Modifier = layout { measurable, constraints ->
    if (!constraints.hasBoundedWidth) {
        val placeable = measurable.measure(constraints)
        return@layout layout(placeable.width, placeable.height) { placeable.placeRelative(0, 0) }
    }
    val extra = by.roundToPx()
    val placeable = measurable.measure(constraints.copy(minWidth = constraints.minWidth + extra, maxWidth = constraints.maxWidth + extra))
    layout(placeable.width - extra, placeable.height) { placeable.placeRelative(0, 0) }
}

/**
 * Lays this out past every edge of the space it is given, without the parent
 * making room for it: [side] further out on the left and the right, [above]
 * and [below], each a part of that space's width. For a picture on a
 * transparent ground that reaches beyond its slot: a deck's pile on the
 * Library's wall ([com.regolith.domain.artwork.DeckLayout.PILE_SIDE]), whose
 * cards' shadows fall past the tile, and which a picture cut to the tile would
 * end in a straight line.
 */
fun Modifier.bleedByWidth(side: Float, above: Float, below: Float): Modifier = layout { measurable, constraints ->
    if (!constraints.hasBoundedWidth || !constraints.hasBoundedHeight) {
        val placeable = measurable.measure(constraints)
        return@layout layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    }
    val width = constraints.maxWidth
    val height = constraints.maxHeight
    val left = (width * side).roundToInt()
    val top = (width * above).roundToInt()
    val bottom = (width * below).roundToInt()
    val placeable = measurable.measure(Constraints.fixed(width + 2 * left, height + top + bottom))
    layout(width, height) { placeable.place(-left, -top) }
}
