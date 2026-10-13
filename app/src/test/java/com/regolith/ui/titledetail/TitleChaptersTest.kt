package com.regolith.ui.titledetail

import com.regolith.domain.playback.Chapter
import com.regolith.domain.playback.ChapterMarks
import com.regolith.domain.playback.ChapterSource
import com.regolith.domain.playback.ChapterStills
import org.junit.Assert.assertEquals
import org.junit.Test

/** [titleChapters]: a video's page shows every chapter the player's sheet does, named and pictured the same way. */
class TitleChaptersTest {
    @Test fun `named and unnamed chapters alike, named as the player's sheet names them`() {
        val chapters = listOf(Chapter(0, null), Chapter(30_000, " Opening shot "), Chapter(60_000, "  "), Chapter(90_000, "Big reveal"))
        val frames = listOf(7_000L, 30_000L, 67_000L, 90_000L)
        assertEquals(
            listOf(
                TitleChapter(0, "Part 1", 7_000),
                TitleChapter(30_000, "Opening shot", 30_000),
                TitleChapter(60_000, "Part 3", 67_000),
                TitleChapter(90_000, "Big reveal", 90_000),
            ),
            titleChapters(chapters, frames),
        )
    }

    @Test fun `an even split is a row of parts, each pictured a quarter in`() {
        val hour = 60 * 60_000L
        val split = ChapterMarks.evenly(hour)
        val row = titleChapters(split, ChapterStills.timesFor(split, hour, ChapterSource.EVEN))
        assertEquals(12, row.size)
        assertEquals(TitleChapter(300_000, "Part 2", 375_000), row[1])
    }

    @Test fun `no chapters, no row`() {
        assertEquals(emptyList<TitleChapter>(), titleChapters(emptyList(), emptyList()))
    }
}
