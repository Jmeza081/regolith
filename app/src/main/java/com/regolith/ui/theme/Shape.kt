package com.regolith.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/** Cards are 14dp, sheets 22dp (top corners), everything tappable is a pill. */
val CardShape = RoundedCornerShape(14.dp)
/** Posters in a grid. */
val TileShape = RoundedCornerShape(12.dp)
/** Small thumbnails in list rows. */
val ThumbShape = RoundedCornerShape(7.dp)
/** Icon boxes in list rows. */
val BoxShape = RoundedCornerShape(10.dp)
/** Dialogs. */
val DialogShape = RoundedCornerShape(20.dp)

/**
 * How wide a dialog is allowed to get: exactly the width it already has on a
 * phone — 411dp of screen less the two 18dp gutters.
 *
 * Dialogs stopped using the platform's own width so they would match the
 * app's surfaces rather than Android's, which is right on a phone and badly
 * wrong on a tablet, where "the gutters" is most of a 900dp window and the
 * card became a banner. Capping it here says the rule in one line — a dialog
 * never gets wider than it is on a phone — and keeps the copy at the line
 * length that was tuned there. Only a NARROW screen makes a dialog smaller.
 */
val DialogMaxWidth = 375.dp
val SheetShape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)
val PillShape = RoundedCornerShape(percent = 50)

/**
 * M3 shape slots mapped onto the same three radii so Material components
 * that pick their own shape (dialogs, menus) still look like Regolith.
 */
val RegolithShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = CardShape,
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(22.dp),
)
