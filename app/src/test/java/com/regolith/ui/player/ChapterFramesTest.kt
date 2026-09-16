package com.regolith.ui.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule that decides when the chapter sheet opens. Every branch here
 * exists because the obvious rule -- wait for every picture -- hangs the
 * button on some real film.
 */
class ChapterFramesTest {
    private fun settled(
        chapters: Int, present: Int, off: Boolean = false,
        sinceRequest: Long = 0, sinceArrival: Long = 0, capacity: Int = 40,
    ) = chapterFramesSettled(chapters, present, off, sinceRequest, sinceArrival, capacity)

    @Test
    fun `it waits while the pictures are still coming`() {
        assertFalse(settled(chapters = 8, present = 0))
        assertFalse(settled(chapters = 8, present = 7))
    }

    @Test
    fun `a full set opens it`() {
        assertTrue(settled(chapters = 8, present = 8))
        assertTrue(settled(chapters = 8, present = 9))
    }

    @Test
    fun `thumbnails off never waits`() {
        assertTrue(settled(chapters = 20, present = 0, off = true))
    }

    @Test
    fun `a film with no chapters never waits`() {
        assertTrue(settled(chapters = 0, present = 0))
    }

    @Test
    fun `more chapters than the index holds never waits`() {
        // The LRU would evict the first frames as the last arrive, so
        // "all present" is unreachable and waiting would never end.
        assertTrue(settled(chapters = 41, present = 3, capacity = 40))
        assertFalse(settled(chapters = 40, present = 3, capacity = 40))
    }

    @Test
    fun `frames that stopped arriving stop the wait`() {
        // A bucket neither extractor can place is dropped for good.
        assertFalse(settled(chapters = 8, present = 6, sinceArrival = FRAMES_STALL_MS - 1))
        assertTrue(settled(chapters = 8, present = 6, sinceArrival = FRAMES_STALL_MS))
        // Nothing has arrived at all: that is the first-frame bound's job.
        assertFalse(settled(chapters = 8, present = 0, sinceArrival = FRAMES_STALL_MS * 2))
    }

    @Test
    fun `a film still filling in is not cut off`() {
        // The bug this rule was rewritten for: a flat 6s cap opened the sheet
        // mid-refill, at four tiles of five, because the media3 fallback takes
        // ~1.2s a frame. Steady progress must keep the sheet shut.
        val gap = 1_200L
        assertFalse(settled(chapters = 5, present = 4, sinceRequest = 6_000, sinceArrival = gap))
        assertFalse(settled(chapters = 5, present = 4, sinceRequest = 12_000, sinceArrival = gap))
        // The stall margin has to clear that gap with room to spare.
        assertTrue("the stall bound must sit above the gap between frames", FRAMES_STALL_MS > gap * 2)
    }

    @Test
    fun `a silent share opens without waiting for the ceiling`() {
        assertFalse(settled(chapters = 8, present = 0, sinceRequest = FRAMES_NO_FIRST_FRAME_MS - 1))
        assertTrue(settled(chapters = 8, present = 0, sinceRequest = FRAMES_NO_FIRST_FRAME_MS))
    }

    @Test
    fun `nothing waits past the ceiling`() {
        // Even mid-arrival, which is the only way this branch is reached.
        assertFalse(settled(chapters = 8, present = 5, sinceRequest = FRAMES_CEILING_MS - 1, sinceArrival = 0))
        assertTrue(settled(chapters = 8, present = 5, sinceRequest = FRAMES_CEILING_MS, sinceArrival = 0))
    }
}
