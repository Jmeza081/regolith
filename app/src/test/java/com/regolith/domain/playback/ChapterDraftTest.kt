package com.regolith.domain.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ChapterDraftTest {
    private val hour = 60 * 60_000L
    private fun seeded() = ChapterDraft.seed(7, ChapterMarks.evenly(hour), hour)

    @Test
    fun `seeding from the even split keeps every part and is not dirty`() {
        val d = seeded()
        assertEquals(ChapterMarks.evenly(hour).size, d.marks.size)
        assertEquals(0L, d.marks.first().startMs)
        assertFalse(d.dirty)
        assertNull(d.selected)
    }

    @Test
    fun `seeding without a start mark adds one, and drops marks past the end`() {
        val d = ChapterDraft.seed(7, listOf(Chapter(600_000, "Late"), Chapter(hour + 1, "Gone")), hour)
        assertEquals(listOf(0L, 600_000L), d.marksMs)
        assertNull(d.marks[0].title)
    }

    @Test
    fun `a mark lands sorted and selected`() {
        val d = ChapterDraft.seed(7, listOf(Chapter(0, null), Chapter(1_200_000, null)), hour).mark(300_000)
        assertEquals(listOf(0L, 300_000L, 1_200_000L), d.marksMs)
        assertEquals(1, d.selected)
        assertTrue(d.dirty)
    }

    @Test
    fun `a mark inside the gap of another only selects it`() {
        val start = ChapterDraft.seed(7, listOf(Chapter(0, null), Chapter(1_200_000, "Two")), hour)
        val d = start.mark(1_200_400)
        assertEquals(start.marksMs, d.marksMs)
        assertEquals(1, d.selected)
        assertFalse(d.dirty)
        // And the ends are out of reach: a mark at 0 is the start mark.
        assertEquals(0, start.mark(0).selected)
        assertEquals(hour - 1_000, start.mark(hour).marksMs.last())
    }

    @Test
    fun `the start mark is pinned`() {
        val d = ChapterDraft.seed(7, listOf(Chapter(0, "Intro"), Chapter(1_200_000, null)), hour)
        assertSame(d, d.move(0, 5_000))
        assertSame(d, d.remove(0))
        assertEquals("Opening", d.rename(0, "Opening").marks[0].title)
    }

    @Test
    fun `moving stays between the neighbours so the index holds`() {
        val d = ChapterDraft.seed(7, listOf(Chapter(0, null), Chapter(600_000, "A"), Chapter(1_200_000, "B")), hour)
        assertEquals(1_199_000L, d.move(1, 2_000_000).marks[1].startMs)
        assertEquals(1_000L, d.move(1, -5).marks[1].startMs)
        assertEquals(hour - 1_000, d.move(2, hour * 2).marks[2].startMs)
        assertEquals("A", d.move(1, 900_000).marks[1].title)
        assertEquals(600_500L, d.nudge(1, 500).marks[1].startMs)
    }

    @Test
    fun `a name is kept as typed and trimmed only on the way out`() {
        val d = seeded().rename(1, "The heist ")
        assertEquals("The heist ", d.marks[1].title)
        assertEquals("The heist", d.chapters[1].title)
        assertNull(seeded().rename(1, "   ").chapters[1].title)
        assertEquals(ChapterDraft.NAME_MAX, seeded().rename(1, "x".repeat(200)).marks[1].title!!.length)
        assertFalse(seeded().rename(1, "").dirty)
    }

    @Test
    fun `removing shifts the selection with the rows`() {
        val d = ChapterDraft.seed(7, listOf(Chapter(0, null), Chapter(600_000, "A"), Chapter(1_200_000, "B")), hour).select(2)
        val gone = d.remove(1)
        assertEquals(listOf(0L, 1_200_000L), gone.marksMs)
        assertEquals(1, gone.selected)
        assertNull(d.select(1).remove(1).selected)
        assertTrue(gone.dirty)
    }

    @Test
    fun `bounds keep a mark a second clear of its neighbours`() {
        val d = ChapterDraft.seed(7, listOf(Chapter(0, null), Chapter(600_000, "A"), Chapter(1_200_000, "B")), hour)
        assertNull(d.bounds(0))
        assertEquals(1_000L..1_199_000L, d.bounds(1))
        assertEquals(601_000L..(hour - 1_000), d.bounds(2))
        assertNull(d.bounds(9))
    }

    @Test
    fun `a typed clock reads the ways people write one`() {
        assertEquals(750_000L, ChapterDraft.parseClock("12:30"))
        assertEquals(750_000L, ChapterDraft.parseClock("0:12:30"))
        assertEquals(3_735_500L, ChapterDraft.parseClock("1:02:15.5"))
        assertEquals(45_000L, ChapterDraft.parseClock(" 45 "))
        assertNull(ChapterDraft.parseClock("12:75"))
        assertNull(ChapterDraft.parseClock("twelve"))
        assertNull(ChapterDraft.parseClock("1:2:3:4"))
        assertNull(ChapterDraft.parseClock(""))
    }

    @Test
    fun `selecting past the end clears`() {
        assertNull(seeded().select(99).selected)
    }
}
