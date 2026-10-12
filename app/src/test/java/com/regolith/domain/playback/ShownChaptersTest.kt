package com.regolith.domain.playback

import org.junit.Assert.assertEquals
import org.junit.Test

/** The one rule the player and the chapter-picture walk share: yours, else the file's own, else the even split. */
class ShownChaptersTest {
    private val hour = 60 * 60_000L
    private val user = listOf(Chapter(0, null), Chapter(600_000, "The heist"))
    private val container = listOf(Chapter(0, "Opening"), Chapter(1_200_000, "Act two"))

    @Test
    fun `what the user wrote wins`() {
        assertEquals(ShownChapters(user, ChapterSource.USER), ShownChapters.of(user, container, hour))
    }

    @Test
    fun `then the file's own markers`() {
        assertEquals(ShownChapters(container, ChapterSource.CONTAINER), ShownChapters.of(emptyList(), container, hour))
    }

    @Test
    fun `then the even split of the runtime`() {
        assertEquals(ShownChapters(ChapterMarks.evenly(hour), ChapterSource.EVEN), ShownChapters.of(emptyList(), emptyList(), hour))
    }

    @Test
    fun `a clip too short to split shows nothing`() {
        assertEquals(ShownChapters(emptyList(), ChapterSource.EVEN), ShownChapters.of(emptyList(), emptyList(), 12_000L))
    }
}
