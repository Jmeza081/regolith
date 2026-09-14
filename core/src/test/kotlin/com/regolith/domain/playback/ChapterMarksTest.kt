package com.regolith.domain.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The interval ladder is the whole design here: the numbers have to stay
 * round, and the count has to stay small enough to be a way of getting
 * somewhere rather than a list to read.
 */
class ChapterMarksTest {

    @Test fun `a runtime is cut at a round interval, never an arithmetic one`() {
        // 40-minute episode: 5-minute parts, not 40/12 = 3m20s ones.
        assertEquals(300_000L, ChapterMarks.intervalFor(40 * 60_000L))
        // Feature length: eleven 10-minute parts still fit, so 10 wins.
        assertEquals(600_000L, ChapterMarks.intervalFor(110 * 60_000L))
        // Two and a half hours: 10-minute parts would be fifteen, so 15 minutes.
        assertEquals(900_000L, ChapterMarks.intervalFor(150 * 60_000L))
        // Half an hour: 5 minutes fits in twelve, so 5 minutes it is.
        assertEquals(300_000L, ChapterMarks.intervalFor(30 * 60_000L))
        // A two-minute clip: twelve 10-second parts fit exactly.
        assertEquals(10_000L, ChapterMarks.intervalFor(120_000L))
        // Three minutes: 10-second parts would be eighteen, so 15 seconds.
        assertEquals(15_000L, ChapterMarks.intervalFor(180_000L))
    }

    @Test fun `every interval on the ladder is a round number of seconds`() {
        assertTrue(ChapterMarks.intervalsMs.all { it % 1_000L == 0L })
        assertEquals(ChapterMarks.intervalsMs.sorted(), ChapterMarks.intervalsMs)
    }

    @Test fun `nothing is ever cut into more than twelve parts`() {
        for (minutes in 1..300) {
            val parts = ChapterMarks.evenly(minutes * 60_000L)
            assertTrue("$minutes min gave ${parts.size} parts", parts.size <= ChapterMarks.MAX_PARTS)
        }
    }

    @Test fun `a runtime past the ladder still gets round minutes`() {
        // Four hours: beyond 15-minute parts, so it falls back to even parts
        // rounded up to the minute — and stays inside the cap.
        val interval = ChapterMarks.intervalFor(4 * 60 * 60_000L)!!
        assertEquals(0L, interval % 60_000L)
        assertTrue(ChapterMarks.evenly(4 * 60 * 60_000L).size <= ChapterMarks.MAX_PARTS)
    }

    @Test fun `parts start at zero and never reach the end`() {
        val duration = 47 * 60_000L
        val parts = ChapterMarks.evenly(duration)
        assertEquals(0L, parts.first().startMs)
        assertTrue(parts.last().startMs < duration)
        assertEquals(parts.map { it.startMs }.sorted(), parts.map { it.startMs })
        assertTrue(parts.all { it.title == null })
    }

    @Test fun `a trailing sliver is absorbed rather than given its own part`() {
        // 61 minutes at 5-minute parts would leave a 1-minute thirteenth.
        val parts = ChapterMarks.evenly(61 * 60_000L)
        assertTrue(parts.size <= ChapterMarks.MAX_PARTS)
        assertTrue((61 * 60_000L - parts.last().startMs) > 60_000L)
    }

    @Test fun `something too short to divide gets nothing`() {
        assertNull(ChapterMarks.intervalFor(5_000L))
        assertTrue(ChapterMarks.evenly(5_000L).isEmpty())
        assertTrue(ChapterMarks.evenly(0L).isEmpty())
    }
}
