package com.regolith.domain.media

import org.junit.Assert.assertEquals
import org.junit.Test

/** Which files go with a video when it is renamed, moved or deleted. */
class CompanionsTest {

    @Test
    fun `the files that share a video's base name are its companions`() {
        val folder = listOf("beach.mp4", "beach.chapters.txt", "beach.en.srt", "beach.jpg", "beach.nfo", "sunset.mp4", "sunset.srt", "notes.txt")
        assertEquals(
            listOf("beach.chapters.txt", "beach.en.srt", "beach.jpg", "beach.nfo"),
            Companions.of("beach.mp4", folder),
        )
    }

    @Test
    fun `case does not matter, as on a share`() {
        assertEquals(listOf("BEACH.EN.SRT"), Companions.of("Beach.mp4", listOf("Beach.mp4", "BEACH.EN.SRT")))
    }

    @Test
    fun `a name that only starts the same is not a companion`() {
        // "beachside" is not "beach" followed by a dot.
        assertEquals(emptyList<String>(), Companions.of("beach.mp4", listOf("beach.mp4", "beachside.srt", "beach-notes.txt")))
    }

    @Test
    fun `another video is never a companion`() {
        assertEquals(listOf("beach.srt"), Companions.of("beach.mp4", listOf("beach.mp4", "beach.part2.mkv", "beach.srt")))
    }

    @Test
    fun `the longest base wins`() {
        val folder = listOf("beach.mp4", "beach.2019.mp4", "beach.srt", "beach.2019.en.srt")
        assertEquals(listOf("beach.srt"), Companions.of("beach.mp4", folder))
        assertEquals(listOf("beach.2019.en.srt"), Companions.of("beach.2019.mp4", folder))
    }

    @Test
    fun `two videos with the same base keep their companions where they are`() {
        val folder = listOf("beach.mp4", "beach.mkv", "beach.en.srt")
        assertEquals(emptyList<String>(), Companions.of("beach.mp4", folder))
        assertEquals(emptyList<String>(), Companions.of("beach.mkv", folder))
    }

    @Test
    fun `videos going together take shared companions only when both go`() {
        val folder = listOf("beach.mp4", "beach.mkv", "beach.en.srt", "sunset.mp4", "sunset.srt")
        assertEquals(listOf("beach.en.srt", "sunset.srt"), Companions.goingWith(listOf("beach.mp4", "beach.mkv", "sunset.mp4"), folder))
        assertEquals(emptyList<String>(), Companions.goingWith(listOf("beach.mp4"), folder))
    }

    @Test
    fun `the folder's own poster stays with the folder`() {
        assertEquals(emptyList<String>(), Companions.of("poster.mp4", listOf("poster.mp4", "poster.jpg")))
    }

    @Test
    fun `a companion is renamed to match its video, tags and all`() {
        assertEquals("sunset.en.srt", Companions.renamed("beach.en.srt", "beach.mp4", "sunset.mp4"))
        assertEquals("Sunset (2019).chapters.txt", Companions.renamed("beach.chapters.txt", "beach.mp4", "Sunset (2019).mp4"))
        // The base keeps the case the video was given; the tail keeps its own.
        assertEquals("sunset.EN.srt", Companions.renamed("Beach.EN.srt", "beach.mp4", "sunset.mp4"))
    }
}
