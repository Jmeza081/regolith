package com.regolith.ui

import com.regolith.domain.fileops.FileOpResult
import com.regolith.domain.fileops.FileOpTarget
import com.regolith.ui.util.DeleteTarget
import com.regolith.ui.util.FileOpMessages
import com.regolith.ui.util.SelectionUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pictures spoken of as pictures (P20): in the dialogs, the messages and
 * the selection's summary, and the two whose going changes another screen —
 * a collection's poster and a video's own picture.
 */
class PictureFileOpsTest {

    private val one = FileOpTarget.other(1)
    private val two = FileOpTarget.other(2)

    @Test
    fun `a batch of pictures is pictures, and a mixed one is still files`() {
        assertEquals("1 picture", FileOpMessages.subjectFor(listOf(one), setOf(one)))
        assertEquals("2 pictures", FileOpMessages.subjectFor(listOf(one, two), setOf(one, two)))
        assertEquals("2 files", FileOpMessages.subjectFor(listOf(one, two), setOf(one)))
        assertEquals("Moved 2 pictures to Archive", FileOpMessages.forResult(FileOpResult(done = listOf(one, two)), "move", "Moved", "Archive", setOf(one, two)))
    }

    @Test
    fun `deleting pictures says pictures, and the poster says what its tile becomes`() {
        val target = DeleteTarget(
            targets = listOf(one, two), names = listOf("poster.jpg", "IMG_1.jpg"), sizeLabel = "6 MB",
            videoCount = 0, folderCount = 0, otherCount = 2, pictureCount = 2,
            posterNote = FileOpMessages.forPosterGoing("Lake house 2024", next = null),
        )
        assertEquals("Delete 2 pictures?", FileOpMessages.deleteTitle(target))
        assertEquals("Delete 2 pictures", FileOpMessages.deleteConfirmLabel(target))
        assertEquals(
            "6 MB leaves the share for good. This can't be undone. It’s Lake house 2024’s poster, so Lake house 2024 shows a mosaic of what’s in it instead.",
            FileOpMessages.deleteBody(target),
        )
    }

    @Test
    fun `a picture beside other files is still a file`() {
        val target = DeleteTarget(
            targets = listOf(one, two), names = listOf("IMG_1.jpg", "Heat.srt"), sizeLabel = "2 MB",
            videoCount = 0, folderCount = 0, otherCount = 2, pictureCount = 1,
        )
        assertEquals("Delete 2 files?", FileOpMessages.deleteTitle(target))
    }

    @Test
    fun `the poster with another picture of its own falls back to that one`() {
        assertEquals("It’s Films’s poster, so Films wears folder.jpg instead.", FileOpMessages.forPosterGoing("Films", "folder.jpg"))
    }

    @Test
    fun `the rename note carries the warning after its own line`() {
        assertEquals(
            "Keeps .jpg. Only this file is renamed. It’s Heat’s picture: with a new name it stops being that video’s.",
            FileOpMessages.forRenameNote(FileOpTarget.Kind.OTHER, "jpg", 0, FileOpMessages.forVideoPicture("Heat")),
        )
    }

    @Test
    fun `a selection of pictures is counted as pictures, and offers Save rather than Download`() {
        val pictures = SelectionUiState(pickedOthers = setOf(1, 2, 3), pickedPictures = setOf(1, 2, 3), otherBytes = 3_000_000, itemCount = 3)
        assertEquals("3 pictures · 3.0 MB", pictures.summary)
        assertTrue(pictures.onlyPictures)
        assertFalse(pictures.canDownload)
        val mixed = SelectionUiState(pickedOthers = setOf(1, 2), pickedPictures = setOf(1), otherBytes = 10, itemCount = 2)
        assertFalse(mixed.onlyPictures)
        assertEquals("2 other files · 10 B", mixed.summary)
    }
}
