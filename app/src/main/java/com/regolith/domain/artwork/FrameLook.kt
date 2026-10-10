package com.regolith.domain.artwork

import kotlin.math.sqrt

/**
 * Whether a frame is worth a tile. One that is all but black, or nearly all
 * one colour, says nothing about the video: a fade, a cut to black, the dark
 * ground behind a studio's logo. The frame grab tries another point in the
 * video when it gets one ([ArtworkCandidates.blankRetryFractions]).
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
}
