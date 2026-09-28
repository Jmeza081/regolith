package com.regolith.domain.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Where a strip's frames are taken. */
class FilmstripTest {
    @Test
    fun `frames sit in the middle of equal slices`() {
        assertEquals(listOf(1_125L, 3_375L, 5_625L, 7_875L, 10_125L, 12_375L, 14_625L, 16_875L), Filmstrip.positions(18_000, 8))
    }

    @Test
    fun `nothing to take until the length is known`() {
        assertTrue(Filmstrip.positions(0, 8).isEmpty())
        assertTrue(Filmstrip.positions(-1, 8).isEmpty())
        assertTrue(Filmstrip.positions(18_000, 0).isEmpty())
    }
}
