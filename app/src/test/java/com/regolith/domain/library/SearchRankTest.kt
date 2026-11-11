package com.regolith.domain.library

import org.junit.Assert.assertEquals
import org.junit.Test

/** Search's "own name first" ordering, before the cap. */
class SearchRankTest {
    @Test fun `own matches first, then the rest, each once, capped`() {
        assertEquals(listOf(3, 1, 2), SearchRank.ownFirst(listOf(3, 1), listOf(1, 2, 3, 4), limit = 3) { it })
    }

    @Test fun `nothing named that way leaves the order as it came`() {
        assertEquals(listOf(5, 6), SearchRank.ownFirst(emptyList(), listOf(5, 6), limit = 40) { it })
    }

    @Test fun `a match by both name and title counts once`() {
        assertEquals(listOf(1, 2), SearchRank.ownFirst(listOf(1, 1), listOf(2, 1), limit = 40) { it })
    }
}
