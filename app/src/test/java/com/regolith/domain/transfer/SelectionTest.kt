package com.regolith.domain.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules a multi-selection obeys. These are what stop a batch from
 * downloading the same file twice, or from offering a tap that does nothing.
 */
class SelectionTest {

    private fun folder(id: Long, path: String, share: Long = 1, files: Int = 0, bytes: Long = 0) =
        FolderPick(folderId = id, shareId = share, relPath = path, fileCount = files, byteCount = bytes)

    private fun file(id: Long, folderPath: String, share: Long = 1, bytes: Long = 0) =
        FilePick(fileId = id, shareId = share, folderRelPath = folderPath, sizeBytes = bytes)

    // ── pathCoveredBy ──────────────────────────────────────────────────

    @Test
    fun `nothing picked covers nothing`() {
        // The opposite of rootsCover, deliberately: "no roots" is a statement
        // about a library's shape, "nothing picked" is a statement about a
        // selection, and it means nothing is picked.
        assertFalse(pathCoveredBy(emptySet(), "Films"))
        assertFalse(pathCoveredBy(emptySet(), ""))
    }

    @Test
    fun `a path covers itself and everything under it`() {
        val paths = setOf("Films")
        assertTrue(pathCoveredBy(paths, "Films"))
        assertTrue(pathCoveredBy(paths, "Films/Arrival (2016)"))
        assertTrue(pathCoveredBy(paths, "Films/Arrival (2016)/Extras"))
    }

    @Test
    fun `a name that merely starts the same is not inside`() {
        // Only the separator says so. The same trap RootsCoverTest guards.
        val paths = setOf("Films")
        assertFalse(pathCoveredBy(paths, "Films Archive"))
        assertFalse(pathCoveredBy(paths, "FilmsOld/Heat.mkv"))
    }

    // ── toggling ───────────────────────────────────────────────────────

    @Test
    fun `toggling a folder on and off leaves nothing`() {
        val pick = folder(1, "Films")
        val on = Selection().toggleFolder(pick)
        assertEquals(1, on.folders.size)
        assertTrue(on.toggleFolder(pick).isEmpty)
    }

    @Test
    fun `picking a parent drops a pick already made beneath it`() {
        val selection = Selection()
            .toggleFolder(folder(2, "Films/Arrival (2016)"))
            .toggleFolder(folder(1, "Films"))
        assertEquals(setOf("Films"), selection.folders.map { it.relPath }.toSet())
    }

    @Test
    fun `picking a parent drops files already picked inside it`() {
        val selection = Selection()
            .toggleFile(file(10, "Films"))
            .toggleFile(file(11, "Films/Extras"))
            .toggleFile(file(12, "Series"))
            .toggleFolder(folder(1, "Films"))
        // Both files under Films are coming with the folder; the one in
        // Series is untouched.
        assertEquals(setOf(12L), selection.files.map { it.fileId }.toSet())
    }

    @Test
    fun `a folder covered by an ancestor cannot be picked`() {
        val selection = Selection()
            .toggleFolder(folder(1, "Films"))
            .toggleFolder(folder(2, "Films/Arrival (2016)"))
        // The row is drawn inert for exactly this reason, so the toggle is a
        // no-op rather than a second pick.
        assertEquals(setOf("Films"), selection.folders.map { it.relPath }.toSet())
    }

    @Test
    fun `a file covered by an ancestor cannot be picked`() {
        val selection = Selection()
            .toggleFolder(folder(1, "Films"))
            .toggleFile(file(10, "Films/Arrival (2016)"))
        assertTrue(selection.files.isEmpty())
    }

    @Test
    fun `unpicking a parent is still allowed while its children are covered`() {
        val selection = Selection().toggleFolder(folder(1, "Films"))
        assertTrue(selection.toggleFolder(folder(1, "Films")).isEmpty)
    }

    @Test
    fun `the same path in two shares is two picks`() {
        val selection = Selection()
            .toggleFolder(folder(1, "Films", share = 1))
            .toggleFolder(folder(2, "Films", share = 2))
        assertEquals(2, selection.folders.size)
    }

    @Test
    fun `a pick in one share does not cover a path in another`() {
        val selection = Selection().toggleFolder(folder(1, "Films", share = 1))
        assertFalse(selection.coveredByAncestor(2, "Films/Arrival (2016)"))
        assertTrue(selection.coveredByAncestor(1, "Films/Arrival (2016)"))
    }

    // ── coveredByAncestor ──────────────────────────────────────────────

    @Test
    fun `a picked folder is not covered by itself`() {
        // It is picked in its own right, so its row stays toggleable.
        val selection = Selection().toggleFolder(folder(1, "Films"))
        assertFalse(selection.coveredByAncestor(1, "Films"))
        assertTrue(selection.coveredByAncestor(1, "Films/Arrival (2016)"))
    }

    @Test
    fun `nothing is covered when nothing is picked`() {
        assertFalse(Selection().coveredByAncestor(1, "Films/Arrival (2016)"))
    }

    // ── itemCount and helpers ──────────────────────────────────────────

    @Test
    fun `itemCount counts picks, not the files they expand to`() {
        val selection = Selection()
            .toggleFolder(folder(1, "Films", files = 200))
            .toggleFile(file(10, "Series"))
        assertEquals(2, selection.itemCount)
    }

    @Test
    fun `hasFolder and hasFile report picks made in their own right`() {
        val selection = Selection()
            .toggleFolder(folder(1, "Films"))
            .toggleFile(file(10, "Series"))
        assertTrue(selection.hasFolder(1))
        assertTrue(selection.hasFile(10))
        assertFalse(selection.hasFolder(2))
        assertFalse(selection.hasFile(11))
    }
}
