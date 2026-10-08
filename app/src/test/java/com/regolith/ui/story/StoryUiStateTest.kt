package com.regolith.ui.story

import com.regolith.domain.playback.StoryPace
import com.regolith.ui.library.PictureTile
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale
import java.util.TimeZone

/** What a story says at the top while it plays, and how long the picture up stays. */
class StoryUiStateTest {
    private val locale = Locale.getDefault()
    private val zone = TimeZone.getDefault()

    @Before
    fun setUp() {
        Locale.setDefault(Locale.UK)
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Lisbon"))
    }

    @After
    fun tearDown() {
        Locale.setDefault(locale)
        TimeZone.setDefault(zone)
    }

    private fun at(text: String) = LocalDateTime.parse(text).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun picture(id: Long, name: String, taken: Long?, modified: Long = at("2026-10-08T12:00:00")) = PictureTile(
        pictureId = id, folderId = 1, shareId = 1, relPath = "Kayak trip/$name", name = name,
        width = 900, height = 1200, takenAtMs = taken, modifiedAtMs = modified, addedAtMs = null, sizeBytes = 1_000, camera = null,
    )

    private val state = StoryUiState(
        album = "Kayak trip",
        pictures = listOf(
            picture(1, "a.jpg", at("2024-08-02T09:12:00")),
            picture(2, "b.gif", taken = null),
            picture(3, "c.jpg", at("2024-08-03T10:00:00")),
        ),
        index = 0,
        loaded = true,
    )

    @Test
    fun `the position says where the story is and when the picture was taken`() {
        assertEquals("1 of 3 · 2 Aug 2024", state.position)
    }

    @Test
    fun `a picture with no date of its own goes by its file's`() {
        assertEquals("2 of 3 · 8 Oct 2026", state.copy(index = 1).position)
    }

    @Test
    fun `a GIF longer than the pace stays until it has played`() {
        val onGif = state.copy(index = 1, gifMs = mapOf(2L to 7_200L))
        assertEquals(7_200L, onGif.durationMs)
        assertEquals(3_000L, onGif.copy(index = 2, pace = StoryPace.THREE).durationMs)
    }
}
