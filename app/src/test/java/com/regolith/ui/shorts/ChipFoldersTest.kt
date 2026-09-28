package com.regolith.ui.shorts

import org.junit.Assert.assertEquals
import org.junit.Test

/** Which folders the sideways panel names as chips, out of the fifty a share may hold. */
class ChipFoldersTest {
    private val folders = listOf(
        ShortsFolder(1, "Beach", 3),
        ShortsFolder(2, "Camera", 40),
        ShortsFolder(3, "Home videos", 12),
        ShortsFolder(4, "Lisbon", 12),
        ShortsFolder(5, "Snow", 7),
        ShortsFolder(6, "Zoo", 1),
    )

    private fun ids(selected: Long?) = chipFolders(folders, selected).map { it.id }

    @Test
    fun `the four holding the most clips, biggest first, ties by name`() {
        assertEquals(listOf(2L, 3L, 4L, 5L), ids(null))
    }

    @Test
    fun `picking one of them moves nothing`() {
        assertEquals(ids(null), ids(4))
    }

    @Test
    fun `a folder picked from the sheet takes the last place`() {
        assertEquals(listOf(2L, 3L, 4L, 6L), ids(6))
    }

    @Test
    fun `a share with fewer folders than chips shows them all`() {
        assertEquals(listOf(2L, 1L), chipFolders(listOf(folders[0], folders[1]), null).map { it.id })
    }
}
