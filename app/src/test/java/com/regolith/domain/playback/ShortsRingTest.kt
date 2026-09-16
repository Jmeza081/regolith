package com.regolith.domain.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The ring's arithmetic, which is the only part of the Shorts pool that can be wrong quietly. */
class ShortsRingTest {
    private val ring = ShortsRing()

    @Test fun `a clip always comes back to the same player`() {
        assertEquals(0, ring.slotFor(0))
        assertEquals(1, ring.slotFor(1))
        assertEquals(2, ring.slotFor(2))
        assertEquals(0, ring.slotFor(3))
        assertEquals(2, ring.slotFor(-1)) // a window at the top of the feed reaches past it
    }

    /**
     * The property the whole design rests on: no two clips being kept warm
     * at once may want the same player. If this ever fails, swiping shows
     * the wrong video rather than crashing, which is worse.
     */
    @Test fun `every position in a window gets its own player`() {
        for (size in 2..5) {
            val r = ShortsRing(size)
            for (index in 0..40) {
                val slots = r.window(index, 100).map { r.slotFor(it) }
                assertEquals("size=$size index=$index", slots.size, slots.distinct().size)
            }
        }
    }

    @Test fun `the window is clipped at both ends of the feed`() {
        assertEquals(listOf(0, 1), ring.window(0, 10))   // nothing behind the first clip
        assertEquals(listOf(4, 5, 6), ring.window(5, 10))
        assertEquals(listOf(8, 9), ring.window(9, 10))   // nothing past the last
        assertEquals(listOf(0), ring.window(0, 1))
        assertEquals(emptyList<Int>(), ring.window(0, 0))
    }

    @Test fun `swiping on releases one clip and prepares one`() {
        assertEquals(listOf(4), ring.released(5, 6, 10))
        assertEquals(listOf(7), ring.added(5, 6, 10))
        // and back again
        assertEquals(listOf(7), ring.released(6, 5, 10))
        assertEquals(listOf(4), ring.added(6, 5, 10))
    }

    @Test fun `a jump with no overlap replaces the whole ring`() {
        assertEquals(listOf(4, 5, 6), ring.released(5, 9, 10))
        assertEquals(listOf(8, 9), ring.added(5, 9, 10))
    }

    @Test fun `staying put changes nothing`() {
        assertTrue(ring.released(5, 5, 10).isEmpty())
        assertTrue(ring.added(5, 5, 10).isEmpty())
    }
}
