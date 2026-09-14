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
    fun `a folder covered by an ancestor never becomes a second pick`() {
        val selection = Selection()
            .toggleFolder(folder(1, "Films"))
            .toggleFolder(folder(2, "Films/Arrival (2016)"))
        // Tapping it takes it OUT (SelectionExclusionTest); what it must never
        // do is add a nested pick that would count the same files twice.
        assertEquals(setOf("Films"), selection.folders.map { it.relPath }.toSet())
    }

    @Test
    fun `a file covered by an ancestor never becomes a second pick`() {
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

/**
 * "This folder, minus these." The rules that let a checked row inside a
 * picked folder be unchecked — the owner's report was that it could not.
 */
class SelectionExclusionTest {

    private fun folder(id: Long, path: String, share: Long = 1) = FolderPick(folderId = id, shareId = share, relPath = path)
    private fun file(id: Long, folderPath: String, share: Long = 1, bytes: Long = 0) =
        FilePick(fileId = id, shareId = share, folderRelPath = folderPath, sizeBytes = bytes)

    @Test
    fun `tapping a file inside a picked folder takes it out, not in`() {
        // The bug: this used to be a no-op, so the only way to leave one
        // episode behind was to unpick the season and re-pick the rest.
        val sel = Selection().toggleFolder(folder(1, "Season 01")).toggleFile(file(10, "Season 01"))
        assertTrue(sel.hasFolder(1))
        assertTrue(sel.isExcludedFile(10))
        assertFalse(sel.hasFile(10))
        assertFalse("an excluded file is not coming", sel.coversFileIn(1, "Season 01") && !sel.isExcludedFile(10))
    }

    @Test
    fun `tapping an excluded file puts it back`() {
        val sel = Selection().toggleFolder(folder(1, "Season 01")).toggleFile(file(10, "Season 01")).toggleFile(file(10, "Season 01"))
        assertFalse(sel.isExcludedFile(10))
        assertTrue("back to coming with the folder, not a direct pick", sel.hasFolder(1) && !sel.hasFile(10))
    }

    @Test
    fun `tapping a subfolder inside a picked folder takes its subtree out`() {
        val sel = Selection().toggleFolder(folder(1, "Series")).toggleFolder(folder(2, "Series/Extras"))
        assertTrue(sel.isExcludedFolder(2))
        assertFalse("a file under the excluded subtree is no longer coming", sel.coversFileIn(1, "Series/Extras"))
        assertFalse(sel.coversFileIn(1, "Series/Extras/Deleted scenes"))
        assertTrue("a sibling still is", sel.coversFileIn(1, "Series/Season 01"))
    }

    @Test
    fun `a file under an excluded subtree can be picked back directly`() {
        // Excluded the Extras folder, then wanted one thing from it after all.
        val sel = Selection()
            .toggleFolder(folder(1, "Series"))
            .toggleFolder(folder(2, "Series/Extras"))
            .toggleFile(file(10, "Series/Extras"))
        assertTrue("it becomes a direct pick rather than an un-exclusion", sel.hasFile(10))
        assertTrue(sel.isExcludedFolder(2))
    }

    @Test
    fun `unpicking the folder drops its exclusions`() {
        // They were only ever "minus these" against a pick that is now gone.
        val sel = Selection()
            .toggleFolder(folder(1, "Series"))
            .toggleFile(file(10, "Series"))
            .toggleFolder(folder(2, "Series/Extras"))
            .toggleFolder(folder(1, "Series"))
        assertTrue(sel.isEmpty)
        assertEquals(0, sel.excludedFiles.size + sel.excludedFolders.size)
    }

    @Test
    fun `picking a folder afresh means all of it`() {
        // Exclusions made under a NARROWER pick go when a broader one arrives.
        val sel = Selection()
            .toggleFolder(folder(2, "Series/Season 01"))
            .toggleFile(file(10, "Series/Season 01"))
            .toggleFolder(folder(1, "Series"))
        assertEquals(setOf("Series"), sel.folders.map { it.relPath }.toSet())
        assertFalse(sel.isExcludedFile(10))
    }

    @Test
    fun `exclusions do not count as picks`() {
        val sel = Selection().toggleFolder(folder(1, "Series")).toggleFile(file(10, "Series")).toggleFolder(folder(2, "Series/Extras"))
        assertEquals(1, sel.itemCount)
    }

    @Test
    fun `exclusions in one share do not reach another`() {
        val sel = Selection()
            .toggleFolder(folder(1, "Series", share = 1))
            .toggleFolder(folder(2, "Series", share = 2))
            .toggleFolder(folder(3, "Series/Extras", share = 1))
        assertTrue(sel.coversFileIn(2, "Series/Extras"))
        assertFalse(sel.coversFileIn(1, "Series/Extras"))
    }

    // ── select all never excludes ──────────────────────────────────────

    @Test
    fun `includeFile on a covered row is a no-op, not an exclusion`() {
        val sel = Selection().toggleFolder(folder(1, "Series")).includeFile(file(10, "Series"))
        assertFalse(sel.isExcludedFile(10))
        assertFalse(sel.hasFile(10))
    }

    @Test
    fun `includeFile on an excluded row puts it back`() {
        val sel = Selection().toggleFolder(folder(1, "Series")).toggleFile(file(10, "Series")).includeFile(file(10, "Series"))
        assertFalse(sel.isExcludedFile(10))
    }

    @Test
    fun `includeFolder on an excluded subtree puts it back`() {
        val sel = Selection().toggleFolder(folder(1, "Series")).toggleFolder(folder(2, "Series/Extras")).includeFolder(folder(2, "Series/Extras"))
        assertFalse(sel.isExcludedFolder(2))
        assertTrue(sel.coversFolder(1, "Series/Extras"))
    }

    // ── what the download job is handed ────────────────────────────────

    @Test
    fun `exclusionsUnder resolves only this pick's own`() {
        val sel = Selection()
            .toggleFolder(folder(1, "Series"))
            .toggleFolder(folder(5, "Films"))
            .toggleFile(file(10, "Series"))
            .toggleFolder(folder(2, "Series/Extras"))
            .toggleFile(file(20, "Films"))
        val series = sel.exclusionsUnder(folder(1, "Series"))
        assertEquals(setOf(10L), series.fileIds)
        assertEquals(setOf("Series/Extras"), series.paths)
        val films = sel.exclusionsUnder(folder(5, "Films"))
        assertEquals(setOf(20L), films.fileIds)
        assertTrue(films.paths.isEmpty())
    }

    @Test
    fun `a pick with nothing left out hands over nothing`() {
        val sel = Selection().toggleFolder(folder(1, "Series"))
        assertTrue(sel.exclusionsUnder(folder(1, "Series")).isEmpty)
    }
}
