package com.regolith.ui.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp

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
