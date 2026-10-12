package com.regolith.domain.playback

import org.junit.Assert.assertEquals
import org.junit.Test

/** Where each chapter's picture is taken from: a moment at its own time, any other chapter a quarter in. */
class ChapterStillsTest {
    private val hour = 60 * 60_000L

    @Test
    fun `an even part's picture is a quarter of the way in`() {
        val parts = ChapterMarks.evenly(hour) // 12 parts of 5 minutes
        val times = ChapterStills.timesFor(parts, hour, ChapterSource.EVEN)
        assertEquals(parts.size, times.size)
        assertEquals(75_000L, times[0])
        assertEquals(300_000L + 75_000L, times[1])
    }

    @Test
    fun `a named mark is a moment, and shows the frame at its own time`() {
        val marks = listOf(Chapter(0, null), Chapter(612_345, "The heist"), Chapter(1_200_000, null))
        val times = ChapterStills.timesFor(marks, hour, ChapterSource.USER)
        assertEquals(612_345L, times[1])
    }

    @Test
    fun `an unnamed mark is not a moment, so it is a quarter in like any part`() {
        val marks = listOf(Chapter(0, null), Chapter(600_000, " "), Chapter(1_200_000, null))
        val times = ChapterStills.timesFor(marks, hour, ChapterSource.USER)
        assertEquals(listOf(150_000L, 750_000L, 1_200_000L + (hour - 1_200_000L) / 4), times)
    }

    @Test
    fun `the file's own names are not moments`() {
        val markers = listOf(Chapter(0, "Opening"), Chapter(1_200_000, "Act two"))
        val times = ChapterStills.timesFor(markers, 2_400_000L, ChapterSource.CONTAINER)
        assertEquals(listOf(300_000L, 1_500_000L), times)
    }

    @Test
    fun `whole seconds, so two runtimes a few milliseconds apart name the same frame`() {
        val marks = listOf(Chapter(0, null), Chapter(1_000_000, null))
        val player = ChapterStills.timesFor(marks, 2_000_043L, ChapterSource.USER)
        val row = ChapterStills.timesFor(marks, 2_000_000L, ChapterSource.USER)
        assertEquals(row, player)
        assertEquals(0L, player.last() % 1_000L)
    }

    @Test
    fun `a picture never comes from before its chapter starts`() {
        val marks = listOf(Chapter(12_345, null), Chapter(12_745, null))
        assertEquals(12_345L, ChapterStills.timesFor(marks, hour, ChapterSource.USER).first())
    }

    @Test
    fun `with the runtime unknown, the last part's picture is its first frame`() {
        val marks = listOf(Chapter(0, null), Chapter(600_000, null))
        assertEquals(600_000L, ChapterStills.timesFor(marks, 0L, ChapterSource.USER).last())
    }
}
