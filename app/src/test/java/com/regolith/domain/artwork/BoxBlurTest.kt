package com.regolith.domain.artwork

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The deck background's blur, on plain pixel arrays. */
class BoxBlurTest {
    private val black = 0xFF000000.toInt()
    private val white = 0xFFFFFFFF.toInt()

    @Test fun `a flat colour stays exactly that colour, edges included`() {
        val teal = 0xFF2A7F86.toInt()
        val out = BoxBlur.blur(IntArray(6 * 4) { teal }, 6, 4, radius = 2)
        assertTrue(out.all { it == teal })
    }

    @Test fun `a bright point spreads evenly round itself and stays brightest`() {
        val w = 9
        val pixels = IntArray(w * w) { black }.also { it[4 * w + 4] = white }
        val out = BoxBlur.blur(pixels, w, w, radius = 1, passes = 2)
        fun red(x: Int, y: Int) = (out[y * w + x] shr 16) and 0xFF
        assertTrue(red(4, 4) > red(3, 4))
        assertTrue(red(3, 4) > red(2, 4))
        assertEquals(red(3, 4), red(5, 4))
        assertEquals(red(4, 3), red(4, 5))
        assertEquals(red(3, 4), red(4, 3))
        assertEquals("two passes of one reach two pixels, no further", 0, red(1, 4))
    }

    @Test fun `alpha is kept, and the pixels given are left alone`() {
        val pixels = IntArray(4) { white }.also { it[0] = black }
        val before = pixels.copyOf()
        val out = BoxBlur.blur(pixels, 2, 2, radius = 1)
        assertTrue(pixels.contentEquals(before))
        assertTrue(out.all { it ushr 24 == 0xFF })
    }

    @Test fun `no radius, no blur`() {
        val pixels = IntArray(4) { if (it == 0) white else black }
        assertTrue(BoxBlur.blur(pixels, 2, 2, radius = 0).contentEquals(pixels))
    }
}
