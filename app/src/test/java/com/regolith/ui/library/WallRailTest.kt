package com.regolith.ui.library

import com.regolith.domain.library.LibraryOrder
import com.regolith.domain.library.LibrarySort
import com.regolith.domain.library.SortDirection
import com.regolith.ui.components.AlphabetIndex
import com.regolith.ui.components.RAIL_AFTER
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When a Library wall gets the A–Z rail: a long one, in A-to-Z order, with
 * somewhere to jump to. The rail itself is the move sheet's, tested there.
 */
class WallRailTest {

    /** [count] films in name order, across the alphabet. */
    private fun films(count: Int) = AlphabetIndex(List(count) { "${'A' + it % 26} film $it" }.sortedBy { it.lowercase() })

    @Test
    fun `a long wall in A to Z order gets the rail`() {
        assertTrue(wallTakesRail(LibraryOrder(), RAIL_AFTER + 1, films(RAIL_AFTER + 1)))
        // Picked back from another sort, Name starts A to Z.
        assertTrue(wallTakesRail(LibraryOrder(LibrarySort.DATE_ADDED).pick(LibrarySort.NAME), 40, films(40)))
    }

    @Test
    fun `ten or fewer is a wall you read, not one you hunt through`() {
        assertFalse(wallTakesRail(LibraryOrder(), RAIL_AFTER, films(RAIL_AFTER)))
    }

    @Test
    fun `Z to A has no rail, and nor does any other order`() {
        assertFalse(wallTakesRail(LibraryOrder(LibrarySort.NAME, SortDirection.DESCENDING), 40, films(40)))
        LibrarySort.entries.filter { it != LibrarySort.NAME }.forEach { sort ->
            assertFalse(sort.label, wallTakesRail(LibraryOrder(sort), 40, films(40)))
            assertFalse(sort.label, wallTakesRail(LibraryOrder(sort, SortDirection.ASCENDING), 40, films(40)))
        }
    }

    @Test
    fun `a wall all under one letter has nowhere to jump`() {
        val seasons = AlphabetIndex(List(14) { "Season ${it + 1}" })
        assertFalse(wallTakesRail(LibraryOrder(), 14, seasons))
    }
}
