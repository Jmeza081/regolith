package com.regolith.ui.titledetail

import com.regolith.domain.playback.Chapter
import org.junit.Assert.assertEquals
import org.junit.Test

/** [namedMoments]: a video's page shows the chapters someone named, in the order they play. */
class TitleMomentsTest {
    @Test fun `only named chapters are moments, in the order they play`() {
        val chapters = listOf(Chapter(90_000, "Big reveal"), Chapter(0, null), Chapter(30_000, "Opening shot"), Chapter(60_000, "  "))
        assertEquals(listOf(TitleMoment(30_000, "Opening shot"), TitleMoment(90_000, "Big reveal")), namedMoments(chapters))
    }

    @Test fun `a name keeps its words but not the spaces round them`() {
        assertEquals(listOf(TitleMoment(5_000, "Car chase")), namedMoments(listOf(Chapter(5_000, " Car chase "))))
    }

    @Test fun `no named chapters, no moments`() {
        assertEquals(emptyList<TitleMoment>(), namedMoments(listOf(Chapter(0, null), Chapter(60_000, ""))))
        assertEquals(emptyList<TitleMoment>(), namedMoments(emptyList()))
    }
}
