package com.regolith.domain.transfer

import org.junit.Assert.assertEquals
import org.junit.Test

/** What the batch notification says, and the arithmetic behind the bar. */
class QueueProgressTest {

    @Test
    fun `permille measures bytes across the batch`() {
        assertEquals(0, QueueProgress.permille(0, 1_000))
        assertEquals(500, QueueProgress.permille(500, 1_000))
        assertEquals(1_000, QueueProgress.permille(1_000, 1_000))
    }

    @Test
    fun `permille is zero when there is nothing to measure`() {
        // The caller draws an indeterminate bar in this case; the number is
        // only a fallback, and it must not divide by zero.
        assertEquals(0, QueueProgress.permille(0, 0))
        assertEquals(0, QueueProgress.permille(500, 0))
        assertEquals(0, QueueProgress.permille(0, -1))
    }

    @Test
    fun `permille never leaves its range`() {
        // A row whose totalBytes was stale can report more done than total.
        assertEquals(1_000, QueueProgress.permille(2_000, 1_000))
        assertEquals(0, QueueProgress.permille(-5, 1_000))
    }

    @Test
    fun `permille has the resolution a large file needs`() {
        // The reason it is permille and not percent: 4 MiB into a 1 GiB file
        // moves the bar three steps, where percent would round it to nothing.
        assertEquals(3, QueueProgress.permille(4L * 1024 * 1024, 1024L * 1024 * 1024))
        // It does truncate rather than round, so a fraction under 1/1000
        // still reads as zero. That is the floor, not a bug.
        assertEquals(0, QueueProgress.permille(1L * 1024 * 1024, 4L * 1024 * 1024 * 1024))
    }

    @Test
    fun `a big first file does not pin the bar`() {
        // Twelve files where the first is 40 GB and the rest 200 MB each.
        val total = 40_000_000_000L + 11 * 200_000_000L
        // Half way through the big one is already 48%, where "1 of 12" would
        // still read as 8% and look stalled.
        assertEquals(473, QueueProgress.permille(20_000_000_000L, total))
    }

    @Test
    fun `label is one-based`() {
        assertEquals("1 of 12", QueueProgress.label(1, 12))
        assertEquals("3 of 12", QueueProgress.label(3, 12))
        assertEquals("12 of 12", QueueProgress.label(12, 12))
    }

    @Test
    fun `label clamps rather than printing nonsense`() {
        assertEquals("12 of 12", QueueProgress.label(13, 12))
        assertEquals("1 of 12", QueueProgress.label(0, 12))
        assertEquals("1 of 1", QueueProgress.label(1, 0))
    }

    @Test
    fun `discovering counts up as the walk finds files`() {
        assertEquals("Finding files…", QueueProgress.discovering(0))
        assertEquals("Finding files… 34", QueueProgress.discovering(34))
    }

    @Test
    fun `discovering says nothing about a count it does not have`() {
        assertEquals("Finding files…", QueueProgress.discovering(-1))
    }
}
