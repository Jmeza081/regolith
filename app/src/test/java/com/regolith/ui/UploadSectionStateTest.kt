package com.regolith.ui

import com.regolith.domain.transfer.UploadCause
import com.regolith.domain.transfer.UploadItem
import com.regolith.domain.transfer.UploadStatus
import com.regolith.ui.browse.UploadKind
import com.regolith.ui.browse.UploadQuestion
import com.regolith.ui.browse.UploadSectionAction
import com.regolith.ui.browse.uploadClash
import com.regolith.ui.browse.uploadSection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the upload section at the top of a folder shows (P16): which action
 * sits beside its eyebrow, when a batch folds into one row, and what the
 * question about taken names says.
 */
class UploadSectionStateTest {
    private fun item(
        name: String = "a.jpg",
        status: UploadStatus = UploadStatus.QUEUED,
        cause: UploadCause? = null,
        size: Long = 1_000,
        done: Long = 0,
        id: Long = 1,
    ) = UploadItem(id, batchId = 1, folderId = 7, sourceUri = "content://x/$name", name = name, sizeBytes = size, bytesDone = done, status = status, cause = cause, causeBytes = null)

    private fun section(vararg items: UploadItem) = uploadSection(items.toList(), "TOWER", "Lisbon 2026")!!

    @Test
    fun `no uploads, no section`() {
        assertNull(uploadSection(emptyList(), "TOWER", "Lisbon 2026"))
    }

    @Test
    fun `while files are going the eyebrow offers to cancel them, row by row`() {
        val s = section(item(status = UploadStatus.RUNNING, done = 250, id = 1), item(id = 2))
        assertEquals(UploadSectionAction.CANCEL_ALL, s.action)
        assertEquals(2, s.rows.size)
        assertNull(s.summary)
        val going = s.rows.first()
        assertEquals(0.25f, going.progress!!, 0.001f)
        assertFalse("a moving bar is red", going.progressMuted)
        assertTrue(going.canRemove)
        assertTrue(going.live)
        assertNull("a file not started has no bar", s.rows[1].progress)
    }

    @Test
    fun `waiting for the share offers to stop waiting`() {
        val s = section(item(status = UploadStatus.PAUSED, done = 500))
        assertEquals(UploadSectionAction.TRY_NOW, s.action)
        assertTrue("paused holds its bar, greyed", s.rows.single().progressMuted)
        assertTrue(s.rows.single().emphatic)
    }

    @Test
    fun `a batch with a failure keeps its rows, so the failure keeps its cause and its Try again`() {
        val s = section(
            item(status = UploadStatus.DONE, id = 1),
            item(name = "clip.mp4", status = UploadStatus.FAILED, cause = UploadCause.SHARE_FULL, done = 800, id = 2),
        )
        assertEquals(UploadSectionAction.RETRY_ALL, s.action)
        assertEquals(2, s.rows.size)
        val failed = s.rows[1]
        assertTrue(failed.canRetry)
        assertTrue("and it can be given up on", failed.canRemove)
        assertFalse(failed.live)
        assertEquals("the bar shows where Try again carries on from", 0.8f, failed.progress!!, 0.001f)
        assertEquals(UploadKind.VIDEO, failed.kind)
    }

    @Test
    fun `failures nothing can fix leave only Clear`() {
        val s = section(item(status = UploadStatus.FAILED, cause = UploadCause.SOURCE_GONE))
        assertEquals(UploadSectionAction.CLEAR, s.action)
        assertFalse(s.rows.single().canRetry)
    }

    @Test
    fun `a batch that all went folds into one row, and says where the photos are`() {
        val s = section(item(status = UploadStatus.DONE, id = 1), item(name = "b.jpg", status = UploadStatus.DONE, id = 2))
        assertEquals(UploadSectionAction.CLEAR, s.action)
        assertTrue(s.rows.isEmpty())
        assertEquals("2 uploaded", s.summary!!.title)
        assertEquals("The photos are below, with this folder's other files.", s.note)
    }

    @Test
    fun `the question counts every taken name and words each answer`() {
        val q = UploadQuestion(
            folderName = "Lisbon 2026",
            serverName = "TOWER",
            picked = 4,
            clashes = listOf(
                uploadClash("clip.mp4", "content://x/clip.mp4", sameFile = false, existingSize = 598_000_000, keptName = "clip (1).mp4"),
                uploadClash("a.jpg", "content://x/a.jpg", sameFile = true, existingSize = 1_000, keptName = "a (1).jpg"),
            ),
        )
        assertEquals("2 of the 4 you picked have a name that is taken", q.subtitle)
        assertEquals("Yours arrives as clip (1).mp4", q.keepBothNote)
        assertEquals("Skip it", q.skipLabel)
        assertEquals("Upload the other 2", q.skipNote)
        assertEquals("The one on TOWER is overwritten", q.replaceNote)
        assertEquals("A different file is there · 598 MB", q.clashes.first().detail)
        assertEquals("Same name and size · skipped as a copy", q.clashes.last().detail)
    }
}
