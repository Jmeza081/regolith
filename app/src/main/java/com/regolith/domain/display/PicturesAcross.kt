package com.regolith.domain.display

/**
 * How many columns an album's mosaic has: pictures, each at its own shape,
 * stacked down columns of one width. Set by pinching the mosaic a step at a
 * time, as a poster wall is ([pinchStep]), and kept apart from the walls'
 * posters per row: a mosaic of photos and a wall of posters read best at
 * different sizes.
 *
 * Two answers, because the two screens are different sizes: the phone (the
 * Fold's cover screen too) from two to four across, three to start with,
 * and the inner display, or any wide window, from three to seven, five to
 * start with. Pure.
 */
enum class PicturesAcross(val fewest: Int, val most: Int, val default: Int) {
    PHONE(2, 4, 3),
    WIDE(3, 7, 5),
    ;

    /** [count] held to this screen's range: what a stored or pinched count becomes. */
    fun clamp(count: Int): Int = count.coerceIn(fewest, most)

    companion object {
        fun of(wide: Boolean): PicturesAcross = if (wide) WIDE else PHONE
    }
}
