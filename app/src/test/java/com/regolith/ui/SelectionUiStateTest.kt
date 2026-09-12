package com.regolith.ui

import com.regolith.ui.util.SelectionUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The words on the selection bar. These are the last thing the user reads
 * before committing to a download, so they are worth pinning: the summary
 * is always the same two facts, and the detail line carries whatever
 * qualifies them.
 */
class SelectionUiStateTest {

    @Test
    fun `nothing picked invites the gesture rather than showing a zero`() {
        val state = SelectionUiState()
        assertEquals("Nothing picked", state.summary)
        assertEquals("Hold or tap a video to start", state.detail)
        assertFalse(state.canDownload)
    }

    @Test
    fun `one video is singular`() {
        val state = SelectionUiState(itemCount = 1, fileCount = 1, byteCount = 2_100_000_000)
        assertEquals("1 video · 2.1 GB", state.summary)
        assertNull(state.detail)
        assertTrue(state.canDownload)
    }

    @Test
    fun `several videos read as a batch`() {
        val state = SelectionUiState(itemCount = 4, fileCount = 34, byteCount = 61_200_000_000)
        assertEquals("34 videos · 61 GB", state.summary)
    }

    @Test
    fun `an estimate says so rather than printing a number it cannot stand behind`() {
        val state = SelectionUiState(itemCount = 1, fileCount = 9, byteCount = 18_400_000_000, estimated = true, unlistedFolders = 1)
        // "18 GB", not "18.4": formatBytes keeps one decimal only under 10,
        // which is the design's own chip rule.
        assertEquals("At least 9 videos · 18 GB", state.summary)
        assertEquals("1 folder not listed yet", state.detail)
        // Still downloadable: the walk is what makes it exact, and that
        // happens after the tap.
        assertTrue(state.canDownload)
    }

    @Test
    fun `a folder nobody has listed at all is counting, not zero`() {
        // fileCount is zero because there is nothing in Room to count, which
        // is not the same statement as "this folder is empty".
        val state = SelectionUiState(itemCount = 1, fileCount = 0, byteCount = 0, estimated = true, unlistedFolders = 1)
        assertEquals("Counting…", state.summary)
    }

    @Test
    fun `unlisted folders are pluralised`() {
        val state = SelectionUiState(itemCount = 2, fileCount = 5, byteCount = 1, estimated = true, unlistedFolders = 2)
        assertEquals("2 folders not listed yet", state.detail)
    }

    @Test
    fun `files already kept are reported beside the total`() {
        val state = SelectionUiState(itemCount = 3, fileCount = 34, byteCount = 1, alreadyKept = 3)
        assertEquals("3 already here", state.detail)
    }

    @Test
    fun `no room kills the button and names the shortfall`() {
        val state = SelectionUiState(itemCount = 4, fileCount = 12, byteCount = 26_700_000_000, hasRoom = false, shortfall = 4_500_000_000)
        assertEquals("Not enough room · free 4.5 GB more", state.detail)
        // Download goes dead rather than red: tapping it could not work.
        assertFalse(state.canDownload)
    }

    @Test
    fun `the shortfall comes first when several things qualify the total`() {
        // Order is worst-first: the thing that stops the download outranks
        // the things that merely describe it.
        val state = SelectionUiState(
            itemCount = 4, fileCount = 12, byteCount = 1,
            estimated = true, unlistedFolders = 1, alreadyKept = 2,
            hasRoom = false, shortfall = 1_000_000_000,
        )
        assertEquals("Not enough room · free 1.0 GB more · 1 folder not listed yet · 2 already here", state.detail)
    }

    @Test
    fun `coversFile is inclusive of the folder holding the file`() {
        val state = SelectionUiState(pickedPaths = mapOf(1L to setOf("Films")))
        // Directly inside the pick: coming either way.
        assertTrue(state.coversFile(1, "Films"))
        // Deeper inside it: also coming.
        assertTrue(state.coversFile(1, "Films/Arrival (2016)"))
        // A name that merely starts the same is not inside.
        assertFalse(state.coversFile(1, "Films Archive"))
        // Another share is another tree.
        assertFalse(state.coversFile(2, "Films"))
    }

    @Test
    fun `coversFile says no when nothing is picked`() {
        assertFalse(SelectionUiState().coversFile(1, "Films"))
    }

    // ── picksInside: the signpost down to a deep pick ───────────────────

    @Test
    fun `a folder reports the picks below it`() {
        // What makes deep picking legible: walk into Series, pick a season,
        // walk back out, and Series still says something happened in there.
        val state = SelectionUiState(pickedPaths = mapOf(1L to setOf("Series/Severance/Season 01")))
        assertEquals(1, state.picksInside(1, "Series"))
        assertEquals(1, state.picksInside(1, "Series/Severance"))
    }

    @Test
    fun `a folder does not count itself as a pick inside it`() {
        // It is picked in its own right and its row already says so; counting
        // it again here would read as "something deeper is picked too".
        val state = SelectionUiState(pickedPaths = mapOf(1L to setOf("Films")))
        assertEquals(0, state.picksInside(1, "Films"))
    }

    @Test
    fun `picked files count towards the folder holding them`() {
        val state = SelectionUiState(
            pickedFileFolders = mapOf(1L to listOf("Series/Severance/Season 01", "Series/Severance/Season 01")),
        )
        assertEquals(2, state.picksInside(1, "Series"))
        assertEquals(2, state.picksInside(1, "Series/Severance/Season 01"))
    }

    @Test
    fun `a sibling branch reports nothing`() {
        val state = SelectionUiState(pickedPaths = mapOf(1L to setOf("Series/Severance/Season 01")))
        assertEquals(0, state.picksInside(1, "Films"))
        // And a name that merely starts the same is a different branch.
        assertEquals(0, state.picksInside(1, "Series Archive"))
    }

    @Test
    fun `the share root sees every pick in the share`() {
        val state = SelectionUiState(
            pickedPaths = mapOf(1L to setOf("Films", "Series/Severance")),
            pickedFileFolders = mapOf(1L to listOf("Home videos")),
        )
        assertEquals(3, state.picksInside(1, ""))
    }

    @Test
    fun `another share is another tree`() {
        val state = SelectionUiState(pickedPaths = mapOf(1L to setOf("Films/Arrival (2016)")))
        assertEquals(0, state.picksInside(2, "Films"))
    }

    // ── exclusions: "this folder, minus these" ──────────────────────────

    @Test
    fun `coversFile stops at an excluded subtree`() {
        val state = SelectionUiState(
            pickedPaths = mapOf(1L to setOf("Series")),
            excludedPaths = mapOf(1L to setOf("Series/Extras")),
        )
        assertTrue(state.coversFile(1, "Series/Season 01"))
        assertFalse(state.coversFile(1, "Series/Extras"))
        assertFalse(state.coversFile(1, "Series/Extras/Deleted scenes"))
    }

    @Test
    fun `coversFolder respects exclusions the same way`() {
        val state = SelectionUiState(
            pickedPaths = mapOf(1L to setOf("Series")),
            excludedPaths = mapOf(1L to setOf("Series/Extras")),
        )
        assertTrue(state.coversFolder(1, "Series/Season 01"))
        assertFalse(state.coversFolder(1, "Series/Extras"))
    }

    @Test
    fun `leftOutInside counts what a folder has lost beneath it`() {
        // From the level above, a pick with holes in it must not look like
        // one without: this is what "All but 3" is drawn from.
        val state = SelectionUiState(
            pickedPaths = mapOf(1L to setOf("Series")),
            excludedPaths = mapOf(1L to setOf("Series/Extras")),
            excludedFileFolders = mapOf(1L to listOf("Series/Season 01", "Series/Season 01")),
        )
        assertEquals(3, state.leftOutInside(1, "Series"))
        assertEquals(2, state.leftOutInside(1, "Series/Season 01"))
        assertEquals(0, state.leftOutInside(1, "Series/Season 02"))
        assertEquals(0, state.leftOutInside(1, "Films"))
    }

    @Test
    fun `the detail line reports what was left out`() {
        val state = SelectionUiState(itemCount = 1, fileCount = 7, byteCount = 1, leftOut = 2)
        assertEquals("2 left out", state.detail)
        assertEquals("1 left out", state.copy(leftOut = 1).detail)
    }
}
