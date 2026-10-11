package com.regolith.ui.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** What the Library's refresh line says, and when it says nothing. */
class RefreshLineTest {
    @Test fun `nothing reading the share, no line`() {
        assertNull(LibraryUiState().refreshLine)
        assertNull("a count left over from a scan is not a scan", LibraryUiState(scanFiles = 40).refreshLine)
    }

    @Test fun `a scan says how far it has got`() {
        assertEquals("Reading the share · 1,234 files so far", LibraryUiState(scanning = true, scanFiles = 1234).refreshLine)
    }

    @Test fun `a collection's own walk speaks over a scan, from its first listing`() {
        assertEquals("Reading the share · 7 files so far", LibraryUiState(scanning = true, scanFiles = 900, walkFiles = 7).refreshLine)
        assertEquals("Reading the share · 0 files so far", LibraryUiState(walkFiles = 0).refreshLine)
    }
}
