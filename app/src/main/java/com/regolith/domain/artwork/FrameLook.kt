package com.regolith.domain.artwork

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Whether a frame is worth a tile. One that is all but black, or nearly all
 * one colour, says nothing about the video: a fade, a cut to black, the dark
 * ground behind a studio's logo. The frame grab tries another point in the
 * video when it gets one ([ArtworkCandidates.blankRetryFractions]).
 *
 * It also finds the black bars baked into a video ([picture]), so a deck's
 * card can take the shape of the picture rather than of the frame.
 *
 * Judged from the frame's brightness at a small grid of points, each 0–255
 * (luma, the way a TV weighs red, green and blue), so it is pure and cheap:
 * the bitmap is shrunk to the grid first.
 */
object FrameLook {
    /**
     * True for a frame that is too dark (on average, or almost all of it) or
     * too flat (barely any difference anywhere) to show what a video is.
     */
    fun blank(luma: IntArray): Boolean {
        if (luma.isEmpty()) return true
        val mean = luma.average()
        val spread = sqrt(luma.sumOf { (it - mean) * (it - mean) } / luma.size)
        val dark = luma.count { it < DARK } / luma.size.toDouble()
        return mean < BLACK || spread < FLAT || dark >= MOSTLY_DARK
    }

    /**
     * The picture inside black bars baked into a video, a widescreen film in
     * a 16:9 frame or a 4:3 one with bars at its sides: `[left, top, right,
     * bottom)` in cells of the [width] × [height] grid [luma], or null when
     * it has none. A deck's card then takes the picture's own shape. Bars
     * come in pairs of one depth, which tells them from a dark sky or floor.
     */
    fun picture(luma: IntArray, width: Int, height: Int): IntArray? {
        if (width <= 0 || height <= 0 || luma.size != width * height) return null
        fun darkRow(y: Int) = (0 until width).all { luma[y * width + it] < BAR }
        fun darkColumn(x: Int) = (0 until height).all { luma[it * width + x] < BAR }
        fun depth(limit: Int, dark: (Int) -> Boolean): Int {
            var n = 0
            while (n < limit && dark(n)) n++
            return n
        }
        val top = depth(height / 2) { darkRow(it) }
        val bottom = depth(height / 2) { darkRow(height - 1 - it) }
        val left = depth(width / 2) { darkColumn(it) }
        val right = depth(width / 2) { darkColumn(width - 1 - it) }
        val rows = pair(top, bottom, height)
        val columns = pair(left, right, width)
        if (!rows && !columns) return null
        val box = intArrayOf(if (columns) left else 0, if (rows) top else 0, width - if (columns) right else 0, height - if (rows) bottom else 0)
        // All dark is a dark frame, not bars round a picture.
        if (box[2] - box[0] < width / 3 || box[3] - box[1] < height / 3) return null
        return box
    }

    /** Two bands, each at least [MIN_BAR] of [extent] deep, near enough the same depth. */
    private fun pair(a: Int, b: Int, extent: Int): Boolean =
        minOf(a, b) >= extent * MIN_BAR && abs(a - b) <= maxOf(1, (maxOf(a, b) * 0.25).toInt())

    /** Luma from a pixel's red, green and blue (0–255 each), weighted as Rec. 601 does. */
    fun luma(red: Int, green: Int, blue: Int): Int = (299 * red + 587 * green + 114 * blue) / 1000

    /** Darker than this on average reads as black. */
    private const val BLACK = 18.0

    /** Less spread than this (a standard deviation) reads as a single flat colour. */
    private const val FLAT = 6.0

    /** A point darker than this counts as dark... */
    private const val DARK = 24

    /** ...and a frame this much of which is dark reads as black with something small on it. */
    private const val MOSTLY_DARK = 0.94

    /** A bar is a band of rows (or columns) all darker than this... */
    private const val BAR = 20

    /** ...at least this much of the frame deep. */
    private const val MIN_BAR = 0.05
}
