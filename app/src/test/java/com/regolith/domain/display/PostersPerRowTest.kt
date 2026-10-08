package com.regolith.domain.display

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [pinchStep] and [PostersPerRow]: a pinch on a poster wall moves one count
 * at a time between four and seven, never past what fits, and lands in the
 * one setting every wall follows.
 */
class PostersPerRowTest {

    @Test
    fun `spreading the fingers steps to fewer, bigger posters`() {
        assertEquals(6, pinchStep(shown = 7, fewer = true, most = 7))
        assertEquals(4, pinchStep(shown = 5, fewer = true, most = 7))
    }

    @Test
    fun `pinching them together steps to more, smaller posters`() {
        assertEquals(5, pinchStep(shown = 4, fewer = false, most = 7))
        assertEquals(7, pinchStep(shown = 6, fewer = false, most = 7))
    }

    @Test
    fun `four across is as big as a pinch goes`() {
        assertNull(pinchStep(shown = 4, fewer = true, most = 7))
    }

    @Test
    fun `seven is the most, even where more would fit`() {
        assertNull(pinchStep(shown = 7, fewer = false, most = 9))
    }

    @Test
    fun `a window too narrow for seven stops at what fits`() {
        // The 739dp emulator fits five: a pinch out from there is a real step,
        // and a pinch in is the end of the range rather than a step to nothing.
        assertNull(pinchStep(shown = 5, fewer = false, most = 5))
        assertEquals(4, pinchStep(shown = 5, fewer = true, most = 5))
    }

    @Test
    fun `a count is kept as the setting for it, held to the range`() {
        assertEquals(PostersPerRow.FOUR, PostersPerRow.ofCount(4))
        assertEquals(PostersPerRow.SEVEN, PostersPerRow.ofCount(7))
        assertEquals(PostersPerRow.SEVEN, PostersPerRow.ofCount(9))
        assertEquals(PostersPerRow.FOUR, PostersPerRow.ofCount(2))
    }

    @Test
    fun `a mosaic's pinch keeps to its own range`() {
        // An album on a phone: two to four across.
        assertEquals(2, pinchStep(shown = 3, fewer = true, most = 4, fewest = 2, top = 4))
        assertNull(pinchStep(shown = 2, fewer = true, most = 4, fewest = 2, top = 4))
        assertNull(pinchStep(shown = 4, fewer = false, most = 9, fewest = 2, top = 4))
        // On the inner display: three to seven.
        assertEquals(3, pinchStep(shown = 4, fewer = true, most = 9, fewest = 3, top = 7))
        assertNull(pinchStep(shown = 3, fewer = true, most = 9, fewest = 3, top = 7))
    }

    @Test
    fun `a mosaic's stored count is held to its screen's range`() {
        assertEquals(3, PicturesAcross.PHONE.default)
        assertEquals(5, PicturesAcross.WIDE.default)
        assertEquals(4, PicturesAcross.PHONE.clamp(7))
        assertEquals(3, PicturesAcross.WIDE.clamp(2))
        assertEquals(PicturesAcross.WIDE, PicturesAcross.of(wide = true))
    }

    @Test
    fun `a setting saved before four existed reads back unchanged`() {
        assertEquals(PostersPerRow.FIVE, PostersPerRow.of("FIVE"))
        assertEquals(PostersPerRow.SEVEN, PostersPerRow.of("SEVEN"))
        assertEquals(PostersPerRow.DEFAULT, PostersPerRow.of(null))
    }
}
