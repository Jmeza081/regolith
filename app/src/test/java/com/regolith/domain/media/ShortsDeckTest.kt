package com.regolith.domain.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** The Shorts feed's order, and what skipping a clip may and may not do to it. */
class ShortsDeckTest {
    private val clips = (1L..12L).toList()
    private fun deal(seed: Long?, skipped: Set<Long> = emptySet()) = ShortsDeck.deal(clips, seed, skipped) { it }

    @Test
    fun `the same seed deals the same order`() {
        assertEquals(deal(42), deal(42))
        assertNotEquals(clips, deal(42))
    }

    @Test
    fun `no seed plays them as given`() {
        assertEquals(clips, deal(null))
    }

    @Test
    fun `skipping a clip leaves every other clip where it was`() {
        val before = deal(42)
        val skippedOne = before[5]
        val after = deal(42, setOf(skippedOne))
        assertEquals(before - skippedOne, after)
        // In particular the clips BEFORE it — the one on screen among them —
        // keep their places, so the feed does not jump under the viewer.
        assertEquals(before.take(5), after.take(5))
    }

    @Test
    fun `undoing a skip puts the clip back exactly where the shuffle had it`() {
        val before = deal(42)
        assertEquals(before, deal(42, emptySet()))
        assertEquals(before, ShortsDeck.deal(clips, 42, setOf(999L)) { it })
    }

    @Test
    fun `shuffling a shorter list is a different deal, which is why skips come off afterwards`() {
        val before = deal(42)
        val skippedOne = before[5]
        val shuffledWithout = ShortsDeck.deal(clips - skippedOne, 42, emptySet()) { it }
        assertNotEquals(before - skippedOne, shuffledWithout)
    }
}
