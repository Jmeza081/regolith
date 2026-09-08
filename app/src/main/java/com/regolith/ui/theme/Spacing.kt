package com.regolith.ui.theme

import androidx.compose.ui.unit.dp

/**
 * The eight-step spacing scale from the design (section 01). Every gap and
 * padding in the app is one of these. There is no 5, no 13, no 26: if a layout
 * seems to need one, the layout is wrong, not the scale.
 *
 * Web analogy: the `--space-*` custom properties of a CSS design system.
 */
object Spacing {
    /** Hairline offsets, badge insets. */
    val s2 = 2.dp
    /** A title over its metadata. */
    val s4 = 4.dp
    /** Chips, poster grids, tab items. */
    val s8 = 8.dp
    /** List rows, thumbnail to text. */
    val s12 = 12.dp
    /** Screen gutter, card padding, block to block. */
    val s18 = 18.dp
    /** Section internals. */
    val s30 = 30.dp
    /** Screen to screen. */
    val s40 = 40.dp
    /** Page margin, section to section. */
    val s56 = 56.dp
}
