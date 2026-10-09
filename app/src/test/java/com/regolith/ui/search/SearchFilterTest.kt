package com.regolith.ui.search

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which files Search's chips keep. On device is the one that matters here:
 * it once kept nothing at all.
 */
class SearchFilterTest {

    private val here = OnDevice(doneFileIds = setOf(7L), deviceShareIds = setOf(3L))

    @Test
    fun `on device keeps a finished download, wherever its share is`() {
        assertTrue(here.has(fileId = 7, shareId = 1))
        assertTrue(SearchFilter.ON_DEVICE.keeps(unwatched = false, uhd = false, onDevice = here.has(7, 1)))
    }

    @Test
    fun `on device keeps what lives on the phone itself, with no download behind it`() {
        // The phone's own videos, and copies adopted from a disconnected server.
        assertTrue(here.has(fileId = 40, shareId = 3))
    }

    @Test
    fun `on device drops a file that is only on a share`() {
        assertFalse(here.has(fileId = 40, shareId = 1))
        assertFalse(SearchFilter.ON_DEVICE.keeps(unwatched = true, uhd = true, onDevice = false))
    }

    @Test
    fun `the other chips ask about the file, not where it is`() {
        assertTrue(SearchFilter.ALL.keeps(unwatched = false, uhd = false, onDevice = false))
        assertTrue(SearchFilter.UNWATCHED.keeps(unwatched = true, uhd = false, onDevice = false))
        assertFalse(SearchFilter.UNWATCHED.keeps(unwatched = false, uhd = true, onDevice = true))
        assertTrue(SearchFilter.UHD.keeps(unwatched = false, uhd = true, onDevice = false))
        assertFalse(SearchFilter.UHD.keeps(unwatched = true, uhd = false, onDevice = true))
    }
}
