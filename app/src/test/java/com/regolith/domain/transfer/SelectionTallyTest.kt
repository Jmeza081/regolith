package com.regolith.domain.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the selection bar prints. The numbers matter because they are the
 * last thing a user sees before committing to a download that might be
 * sixty gigabytes.
 */
class SelectionTallyTest {

    private fun folder(id: Long, path: String, files: Int, bytes: Long, listed: Boolean = true) =
        FolderPick(folderId = id, shareId = 1, relPath = path, fileCount = files, byteCount = bytes, listed = listed)

    private fun file(id: Long, bytes: Long, folderPath: String = "Series") =
        FilePick(fileId = id, shareId = 1, folderRelPath = folderPath, sizeBytes = bytes)

    @Test
    fun `an empty selection totals nothing`() {
        val tally = Selection().tally()
        assertEquals(0, tally.fileCount)
        assertEquals(0L, tally.byteCount)
        assertFalse(tally.estimated)
    }

    @Test
    fun `files add their own sizes`() {
        val tally = Selection()
            .toggleFile(file(10, 2_100_000_000))
            .toggleFile(file(11, 2_000_000_000))
            .tally()
        assertEquals(2, tally.fileCount)
        assertEquals(4_100_000_000, tally.byteCount)
    }

    @Test
    fun `a folder contributes its own direct counts`() {
        val tally = Selection().toggleFolder(folder(1, "Films", files = 9, bytes = 18_400_000_000)).tally()
        assertEquals(9, tally.fileCount)
        assertEquals(18_400_000_000, tally.byteCount)
    }

    @Test
    fun `a nested pick is dropped before it can be counted twice`() {
        // The toggle rule, seen through the tally: picking the parent after
        // the child leaves one pick, so 9 files rather than 9 + 4.
        val tally = Selection()
            .toggleFolder(folder(2, "Films/Extras", files = 4, bytes = 1_200_000_000))
            .toggleFolder(folder(1, "Films", files = 9, bytes = 18_400_000_000))
            .tally()
        assertEquals(9, tally.fileCount)
        assertEquals(18_400_000_000, tally.byteCount)
    }

    @Test
    fun `a file already picked is not counted again when its folder is picked over it`() {
        // The double-count guard. (Tapping a file INSIDE an existing folder
        // pick is a different act — it takes the file out; see
        // SelectionTallyExclusionTest.)
        val tally = Selection()
            .toggleFile(file(10, 2_100_000_000, folderPath = "Films"))
            .toggleFolder(folder(1, "Films", files = 9, bytes = 18_400_000_000))
            .tally()
        assertEquals(9, tally.fileCount)
        assertEquals(18_400_000_000, tally.byteCount)
    }

    @Test
    fun `folders and files add up together`() {
        val tally = Selection()
            .toggleFolder(folder(1, "Films", files = 9, bytes = 18_000_000_000))
            .toggleFile(file(10, 2_000_000_000, folderPath = "Series"))
            .tally()
        assertEquals(10, tally.fileCount)
        assertEquals(20_000_000_000, tally.byteCount)
    }

    @Test
    fun `an unlisted folder makes the total an estimate`() {
        // Its fileCount is zero because nobody has looked inside it, so the
        // bar must say "counting" rather than print a number that is wrong.
        val tally = Selection().toggleFolder(folder(1, "Series", files = 0, bytes = 0, listed = false)).tally()
        assertTrue(tally.estimated)
        assertEquals(1, tally.unlistedFolders)
        assertEquals(0, tally.fileCount)
    }

    @Test
    fun `a listed folder is not an estimate`() {
        val tally = Selection().toggleFolder(folder(1, "Films", files = 9, bytes = 1)).tally()
        assertFalse(tally.estimated)
        assertEquals(0, tally.unlistedFolders)
    }

    @Test
    fun `files already on the device are reported, not subtracted`() {
        // "34 videos · 3 already here" tells you more than "31 videos".
        val tally = Selection()
            .toggleFile(file(10, 1_000))
            .toggleFile(file(11, 1_000))
            .toggleFile(file(12, 1_000))
            .tally(doneFileIds = setOf(10L, 11L))
        assertEquals(3, tally.fileCount)
        assertEquals(2, tally.alreadyKept)
    }

    @Test
    fun `a file not in the selection does not count as already kept`() {
        val tally = Selection().toggleFile(file(10, 1_000)).tally(doneFileIds = setOf(99L))
        assertEquals(0, tally.alreadyKept)
    }
}

class SelectionTallyExclusionTest {
    private fun folder(id: Long, path: String, files: Int, bytes: Long) =
        FolderPick(folderId = id, shareId = 1, relPath = path, fileCount = files, byteCount = bytes)
    private fun file(id: Long, folderPath: String, bytes: Long) =
        FilePick(fileId = id, shareId = 1, folderRelPath = folderPath, sizeBytes = bytes)

    @Test
    fun `a file taken out of a pick comes off the total`() {
        val tally = Selection()
            .toggleFolder(folder(1, "Season 01", files = 9, bytes = 9_000))
            .toggleFile(file(10, "Season 01", bytes = 1_000))
            .tally()
        assertEquals(8, tally.fileCount)
        assertEquals(8_000L, tally.byteCount)
        assertEquals(1, tally.leftOut)
    }

    @Test
    fun `an excluded subfolder is reported but its counts are the presenter's to remove`() {
        // The domain tally only knows a pick's OWN direct counts; a subfolder's
        // rows live in Room and the presenter skips them. Here it is counted
        // as left out and nothing is subtracted, so the total never goes below
        // what the pick itself holds.
        val tally = Selection()
            .toggleFolder(folder(1, "Series", files = 2, bytes = 2_000))
            .toggleFolder(folder(2, "Series/Extras", files = 4, bytes = 4_000))
            .tally()
        assertEquals(2, tally.fileCount)
        assertEquals(1, tally.leftOut)
    }

    @Test
    fun `the total never goes negative`() {
        // A file excluded from a folder whose counts are stale at zero.
        val tally = Selection()
            .toggleFolder(folder(1, "Season 01", files = 0, bytes = 0))
            .toggleFile(file(10, "Season 01", bytes = 1_000))
            .tally()
        assertEquals(0, tally.fileCount)
        assertEquals(0L, tally.byteCount)
    }
}
