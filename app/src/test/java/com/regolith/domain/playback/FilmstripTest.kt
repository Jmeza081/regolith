package com.regolith.domain.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Where a strip's frames are taken, and the buckets that keep them apart. */
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

    @Test
    fun `a short cut into eight gets eight different frames, not two`() {
        // The bug this guards against: a film's 10 s buckets put all eight of
        // an 18-second short's frames into two buckets, so the strip showed
        // the same two pictures four times each.
        for (duration in listOf(3_000L, 9_000L, 18_000L, 18_001L, 59_999L, 60_000L)) {
            val index = FrameIndex<Unit>(intervalMs = Filmstrip.sliceMs(duration, 8))
            val buckets = Filmstrip.positions(duration, 8).map(index::bucketOf)
            assertEquals("$duration ms: one bucket per frame", (0L until 8L).toList(), buckets)
        }
    }

    @Test
    fun `a film's own nine frames at its own slices land one per bucket too`() {
        val duration = 2 * 60 * 60 * 1000L
        val index = FrameIndex<Unit>(intervalMs = Filmstrip.sliceMs(duration, 9))
        assertEquals((0L until 9L).toList(), Filmstrip.positions(duration, 9).map(index::bucketOf))
    }

    @Test
    fun `a slice is never zero, however short the clip`() {
        assertEquals(1L, Filmstrip.sliceMs(3, 8))
        assertEquals(1L, Filmstrip.sliceMs(0, 8))
    }
}
