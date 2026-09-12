package com.regolith.player

import com.regolith.domain.playback.Chapter
import com.regolith.domain.playback.ChapterSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** P9: the player shows one kind of chapter at a time — yours, the file's own, or the even split, in that order. */
class PlaybackStateChaptersTest {
    private val container = listOf(Chapter(0, "Opening"), Chapter(1_200_000, "Act two"))
    private val user = listOf(Chapter(0, null), Chapter(600_000, "The heist"))

    @Test
    fun `nothing written and nothing in the file means the even split`() {
        val s = PlaybackState(durationMs = 40 * 60_000L, chaptersScanned = true)
        assertEquals(ChapterSource.EVEN, s.chapterSource)
        assertEquals(8, s.chapters.size)
        assertEquals("Part 3", s.chapterLabelAt(11 * 60_000L))
    }

    @Test
    fun `the file's own markers beat the split`() {
        val s = PlaybackState(durationMs = 40 * 60_000L, chaptersScanned = true, containerChapters = container)
        assertEquals(ChapterSource.CONTAINER, s.chapterSource)
        assertEquals(container, s.chapters)
        assertEquals("Act two", s.chapterLabelAt(1_500_000))
    }

    @Test
    fun `what the user wrote beats both, and an unnamed mark still has a label`() {
        val s = PlaybackState(durationMs = 40 * 60_000L, chaptersScanned = true, containerChapters = container, userChapters = user)
        assertEquals(ChapterSource.USER, s.chapterSource)
        assertEquals(user, s.chapters)
        assertEquals("Part 1", s.chapterLabelAt(1_000))
        assertEquals("The heist", s.chapterLabelAt(700_000))
    }

    @Test
    fun `revert is just the rows going away`() {
        val written = PlaybackState(durationMs = 40 * 60_000L, chaptersScanned = true, containerChapters = container, userChapters = user)
        val reverted = written.copy(userChapters = emptyList())
        assertEquals(ChapterSource.CONTAINER, reverted.chapterSource)
    }

    @Test
    fun `no runtime yet means no label`() {
        assertNull(PlaybackState().chapterLabelAt(0))
    }
}
