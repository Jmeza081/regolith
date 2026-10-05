package com.regolith.ui

import com.regolith.domain.fileops.FileOpTarget
import com.regolith.domain.fileops.ReadOnlySource
import com.regolith.ui.util.DeleteTarget
import com.regolith.ui.util.FileOpMessages
import com.regolith.ui.util.FileVerbs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * What the delete dialog says when only videos are going, in Browse and on
 * a video's own page: the files that go with them are named, because
 * nothing on screen shows them going.
 */
class DeleteMessageTest {

    @Test
    fun `one video on its own reads as it always did`() {
        assertEquals(
            "beach.mp4 leaves the share for good — 1.2 GB. This can't be undone, and the chapters you wrote and where you left off go with it.",
            FileOpMessages.forDeletingVideos(listOf("beach.mp4"), "1.2 GB", companions = 0),
        )
    }

    @Test
    fun `one video says the files that share its name go too`() {
        assertEquals(
            "beach.mp4 and the 3 files that share its name leave the share for good — 1.2 GB. This can't be undone, and the chapters you wrote and where you left off go with it.",
            FileOpMessages.forDeletingVideos(listOf("beach.mp4"), "1.2 GB", companions = 3),
        )
        assertEquals(
            "beach.mp4 and the file that shares its name leave the share for good — 1.2 GB. This can't be undone, and the chapters you wrote and where you left off go with it.",
            FileOpMessages.forDeletingVideos(listOf("beach.mp4"), "1.2 GB", companions = 1),
        )
    }

    @Test
    fun `several videos are counted by size, with their files`() {
        assertEquals(
            "4.1 GB leaves the share for good, with the 5 files that share the videos' names. This can't be undone, and the chapters you wrote and where you left off go with them.",
            FileOpMessages.forDeletingVideos(listOf("beach.mp4", "sunset.mp4"), "4.1 GB", companions = 5),
        )
    }

    @Test
    fun `a folder and videos together say the folder takes everything, and count the videos' files`() {
        val target = DeleteTarget(
            targets = listOf(FileOpTarget.folder(1), FileOpTarget.file(2)),
            names = listOf("Films", "beach.mp4"),
            sizeLabel = "9 GB",
            videoCount = 5,
            folderCount = 1,
            companionCount = 2,
        )
        assertEquals("Delete 2 items?", FileOpMessages.deleteTitle(target))
        assertEquals("Delete 2 items", FileOpMessages.deleteConfirmLabel(target))
        assertEquals(
            "1 folder and 1 video leave the share for good — 5 videos · 9 GB in all. So do the 2 files that share the picked videos' names. " +
                "A folder takes everything inside it, not just its videos. This can't be undone.",
            FileOpMessages.deleteBody(target),
        )
    }

    @Test
    fun `the rename note says the files that share a video's name follow it`() {
        assertEquals(
            "Keeps .mp4 — chapters and your place follow the new name, and the 3 files that share its name are renamed to match.",
            FileOpMessages.forRenameNote(isFolder = false, ext = "mp4", companions = 3),
        )
        assertEquals("Keeps .mp4 — chapters and your place follow the new name.", FileOpMessages.forRenameNote(isFolder = false, ext = "mp4", companions = 0))
        assertEquals("Everything inside keeps its place — the folder moves as one.", FileOpMessages.forRenameNote(isFolder = true, ext = "", companions = 0))
    }

    @Test
    fun `nothing is offered for the phone's own videos`() {
        val verbs = FileVerbs.of(itemCount = 1, shares = 1, readOnly = ReadOnlySource.PHONE)
        assertFalse(verbs.canMove || verbs.canRename || verbs.canDelete)
        assertEquals("Videos on this phone can't be changed here", verbs.hint)
    }
}
