package com.regolith.domain.library

import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryOrderTest {
    @Test fun `a new criterion starts in its natural direction`() {
        assertEquals(LibraryOrder(LibrarySort.NAME, SortDirection.ASCENDING), LibraryOrder())
        assertEquals(SortDirection.DESCENDING, LibraryOrder().pick(LibrarySort.DATE_ADDED).direction)
        assertEquals(SortDirection.ASCENDING, LibraryOrder(LibrarySort.RUNTIME).pick(LibrarySort.NAME).direction)
    }

    @Test fun `picking the one in use reverses it, and again puts it back`() {
        val newest = LibraryOrder(LibrarySort.DATE_ADDED)
        val oldest = newest.pick(LibrarySort.DATE_ADDED)
        assertEquals(LibraryOrder(LibrarySort.DATE_ADDED, SortDirection.ASCENDING), oldest)
        assertEquals(newest, oldest.pick(LibrarySort.DATE_ADDED))
    }

    @Test fun `a reversed criterion forgets its direction when you move away`() {
        val zToA = LibraryOrder(LibrarySort.NAME, SortDirection.DESCENDING)
        assertEquals(LibraryOrder(LibrarySort.NAME), zToA.pick(LibrarySort.FILE_SIZE).pick(LibrarySort.NAME))
    }

    @Test fun `every criterion says both directions in words`() {
        assertEquals("Newest first", LibrarySort.DATE_ADDED.directionLabel(SortDirection.DESCENDING))
        assertEquals("Z to A", LibrarySort.NAME.directionLabel(SortDirection.DESCENDING))
        LibrarySort.entries.forEach {
            assert(it.directionLabel(SortDirection.ASCENDING) != it.directionLabel(SortDirection.DESCENDING))
        }
    }
}
