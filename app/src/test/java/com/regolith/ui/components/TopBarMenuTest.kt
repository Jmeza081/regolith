package com.regolith.ui.components

import com.regolith.domain.library.LibrarySort
import com.regolith.domain.library.SortDirection
import com.regolith.domain.library.ViewMode
import org.junit.Assert.assertEquals
import org.junit.Test

/** The More sheet's settings: they show their state, and choosing the one in use does nothing. */
class TopBarMenuTest {
    @Test fun `sort says its state in words`() {
        assertEquals("Name, A to Z", sortMenuItem(LibrarySort.NAME, SortDirection.ASCENDING, "t") {}.note)
        assertEquals("Date added, Newest first", sortMenuItem(LibrarySort.DATE_ADDED, SortDirection.DESCENDING, "t") {}.note)
    }

    @Test fun `view lights the mode in use and toggles only to the other`() {
        var toggles = 0
        val grid = viewModeChoice(ViewMode.GRID, "t") { toggles++ }
        assertEquals(0, grid.selected)
        grid.onSelect(0)
        assertEquals(0, toggles)
        grid.onSelect(1)
        assertEquals(1, toggles)
        assertEquals(1, viewModeChoice(ViewMode.ROWS, "t") {}.selected)
    }
}
