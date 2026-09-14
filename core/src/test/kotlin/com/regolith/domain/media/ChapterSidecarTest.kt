package com.regolith.domain.media

import com.regolith.domain.playback.Chapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChapterSidecarTest {
    private val chapters = listOf(Chapter(0, "Intro"), Chapter(750_000, "The heist"), Chapter(2_465_500, null), Chapter(3_735_000, "Aftermath"))

    @Test
    fun `writes mkvmerge's simple format, sorted and padded`() {
        val text = ChapterSidecar.format(chapters.shuffled())
        assertEquals(
            """
            CHAPTER01=00:00:00.000
            CHAPTER01NAME=Intro
            CHAPTER02=00:12:30.000
            CHAPTER02NAME=The heist
            CHAPTER03=00:41:05.500
            CHAPTER03NAME=
            CHAPTER04=01:02:15.000
            CHAPTER04NAME=Aftermath

            """.trimIndent(),
            text,
        )
    }

    @Test
    fun `round trips`() {
        assertEquals(chapters, ChapterSidecar.parse(ChapterSidecar.format(chapters)))
    }

    @Test
    fun `reads what people and other tools write`() {
        val text = "CHAPTER02NAME=Two\r\nchapter02=12:30\r\n\r\nCHAPTER01=00:00:00.000\r\nCHAPTER01NAME=  Intro  \r\nnonsense line\r\nCHAPTER03=1:02:15.5\r\nCHAPTER03NAME=Part 3\r\n"
        assertEquals(listOf(Chapter(0, "Intro"), Chapter(750_000, "Two"), Chapter(3_735_500, null)), ChapterSidecar.parse(text))
    }

    @Test
    fun `adds the start mark, drops what is past the end, and keeps one per time`() {
        val text = "CHAPTER01=00:10:00.000\nCHAPTER01NAME=Ten\nCHAPTER02=00:10:00.000\nCHAPTER02NAME=Ten again\nCHAPTER03=09:00:00.000\nCHAPTER03NAME=Late\n"
        assertEquals(listOf(Chapter(0, null), Chapter(600_000, "Ten")), ChapterSidecar.parse(text, durationMs = 3_600_000))
        assertEquals(3, ChapterSidecar.parse(text).size)
    }

    @Test
    fun `garbage is nothing, not a crash`() {
        assertEquals(emptyList<Chapter>(), ChapterSidecar.parse(""))
        assertEquals(emptyList<Chapter>(), ChapterSidecar.parse("hello\nCHAPTER01=soon\n"))
        assertEquals(emptyList<Chapter>(), ChapterSidecar.parse("CHAPTER01=12:75\n"))
    }

    @Test
    fun `caps the count`() {
        val text = buildString { for (i in 1..1200) append("CHAPTER%03d=%s\nCHAPTER%03dNAME=x\n".format(i, ChapterSidecar.formatTimestamp(i * 1000L), i)) }
        assertEquals(ChapterSidecar.MAX_CHAPTERS, ChapterSidecar.parse(text).size)
    }

    @Test
    fun `names sidecars and reads them back`() {
        assertEquals("Heat.1995.chapters.txt", ChapterSidecar.sidecarNameFor("Heat.1995.mkv"))
        assertNull(ChapterSidecar.sidecarNameFor("poster.jpg"))
        assertEquals("Heat.1995", ChapterSidecar.basenameOf("Heat.1995.chapters.txt"))
        assertNull(ChapterSidecar.basenameOf("Heat.1995.mkv"))
        assertNull(ChapterSidecar.basenameOf(".chapters.txt"))
    }

    @Test
    fun `timestamps`() {
        assertEquals(3_735_500L, ChapterSidecar.parseTimestamp("01:02:15.5"))
        assertEquals(3_735_567L, ChapterSidecar.parseTimestamp("1:02:15.567891"))
        assertEquals(750_000L, ChapterSidecar.parseTimestamp("12:30"))
        assertNull(ChapterSidecar.parseTimestamp("90"))
        assertEquals("01:02:15.500", ChapterSidecar.formatTimestamp(3_735_500L))
    }
}
