package com.regolith.ui.search

import com.regolith.domain.playback.ChapterMatch
import org.junit.Assert.assertEquals
import org.junit.Test

/** Which points of interest Search shows beside its matches. */
class PointsOfInterestTest {

    private fun mark(fileId: Long, startMs: Long = 0) =
        ChapterMatch(fileId = fileId, startMs = startMs, title = "Big reveal", fileName = "$fileId.mkv", fileTitle = null, shareId = 1, fileRelPath = "Films/$fileId.mkv")

    private val marks = listOf(mark(1), mark(1, 9_000), mark(2), mark(3))

    @Test
    fun `with a chip, the moments are those in the films listed, as the filter chips left them`() {
        // Film 2 was dropped by, say, 4K: its moment goes with it.
        assertEquals(listOf(mark(1), mark(1, 9_000), mark(3)), pointsOfInterest(marks, chip = "Big reveal", SearchFilter.UHD, kept = setOf(1, 3)))
        assertEquals(marks, pointsOfInterest(marks, chip = "Big reveal", SearchFilter.ALL, kept = setOf(1, 2, 3)))
    }

    @Test
    fun `without a chip, the names a query finds show under All only`() {
        assertEquals(marks, pointsOfInterest(marks, chip = null, SearchFilter.ALL, kept = emptySet()))
        assertEquals(emptyList<ChapterMatch>(), pointsOfInterest(marks, chip = null, SearchFilter.UNWATCHED, kept = setOf(1, 2, 3)))
    }
}
