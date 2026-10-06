package com.regolith.domain.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The upload queue's rules and every sentence it says (P16). Pure, so the
 * wording a notification uses is checked here rather than in a shade.
 */
class UploadsTest {

    private fun item(
        name: String = "20260914_183022.jpg",
        size: Long = 4_200_000,
        done: Long = 0,
        status: UploadStatus = UploadStatus.QUEUED,
        cause: UploadCause? = null,
        causeBytes: Long? = null,
        batch: Long = 1,
        folder: Long = 7,
        id: Long = 0,
    ) = UploadItem(id, batch, folder, "content://x/$name", name, size, done, status, cause, causeBytes)

    // ── Names ───────────────────────────────────────────────────────────

    @Test
    fun `a free name is kept`() {
        assertEquals("IMG_1.jpg", UploadNames.keepBoth("IMG_1.jpg", setOf("IMG_2.jpg")))
    }

    @Test
    fun `a taken name gets a number before its extension`() {
        assertEquals("IMG_1 (1).jpg", UploadNames.keepBoth("IMG_1.jpg", setOf("IMG_1.jpg")))
    }

    @Test
    fun `the number climbs past copies already there`() {
        assertEquals("IMG_1 (3).jpg", UploadNames.keepBoth("IMG_1.jpg", setOf("IMG_1.jpg", "IMG_1 (1).jpg", "img_1 (2).JPG")))
    }

    @Test
    fun `names are compared the way SMB compares them, without case`() {
        assertEquals("a (1).mp4", UploadNames.keepBoth("a.mp4", setOf("A.MP4")))
    }

    @Test
    fun `a name without an extension is numbered at its end`() {
        assertEquals("README (1)", UploadNames.keepBoth("README", setOf("README")))
    }

    @Test
    fun `same name and size is the same file, and an unknown size never is`() {
        assertTrue(UploadNames.isSameFile(existingSize = 4_200_000, pickedSize = 4_200_000))
        assertFalse(UploadNames.isSameFile(existingSize = 4_200_000, pickedSize = 4_100_000))
        assertFalse(UploadNames.isSameFile(existingSize = 0, pickedSize = -1))
    }

    // ── The tally ───────────────────────────────────────────────────────

    @Test
    fun `nothing owed is no tally`() {
        assertNull(UploadTally.of(listOf(item(status = UploadStatus.DONE))))
    }

    @Test
    fun `a batch keeps its finished files, so the count does not restart`() {
        val tally = UploadTally.of(
            listOf(
                item(status = UploadStatus.DONE, size = 100),
                item(status = UploadStatus.RUNNING, size = 100, done = 50),
                item(status = UploadStatus.QUEUED, size = 200),
            ),
        )!!
        assertEquals(3, tally.files)
        assertEquals("the second is going", 2, tally.position)
        assertEquals(150, tally.bytesDone)
        assertEquals(400, tally.bytesTotal)
    }

    @Test
    fun `a failed file's bytes leave the total instead of holding the bar short`() {
        val tally = UploadTally.of(
            listOf(
                item(status = UploadStatus.FAILED, cause = UploadCause.SHARE_FULL, size = 600, done = 480),
                item(status = UploadStatus.RUNNING, size = 100, done = 25),
            ),
        )!!
        assertEquals(100, tally.bytesTotal)
        assertEquals(0.25f, tally.fraction!!, 0.001f)
    }

    @Test
    fun `an old finished batch is not counted with a new one`() {
        val tally = UploadTally.of(
            listOf(
                item(batch = 1, status = UploadStatus.DONE),
                item(batch = 2, status = UploadStatus.QUEUED),
            ),
        )!!
        assertEquals(1, tally.files)
    }

    @Test
    fun `paused means nothing is moving`() {
        assertTrue(UploadTally.of(listOf(item(status = UploadStatus.PAUSED), item()))!!.paused)
        assertFalse(UploadTally.of(listOf(item(status = UploadStatus.PAUSED), item(status = UploadStatus.RUNNING)))!!.paused)
    }

    @Test
    fun `uploads into two folders have no one folder`() {
        assertNull(UploadTally.of(listOf(item(folder = 1), item(folder = 2)))!!.folderId)
        assertEquals(1L, UploadTally.of(listOf(item(folder = 1), item(folder = 1)))!!.folderId)
    }

    // ── What a row says ─────────────────────────────────────────────────

    private fun status(i: UploadItem) = UploadWording.status(i, "TOWER", "Lisbon 2026")

    @Test
    fun `a row says where it stands`() {
        assertEquals("Waiting · 4.2 MB", status(item()))
        assertEquals("434 MB of 612 MB · 70%", status(item(size = 612_000_000, done = 434_000_000, status = UploadStatus.RUNNING)))
        assertEquals("Paused at 434 MB of 612 MB · resumes on its own", status(item(size = 612_000_000, done = 434_000_000, status = UploadStatus.PAUSED)))
        assertEquals("Waiting · 434 MB of 612 MB sent", status(item(size = 612_000_000, done = 434_000_000)))
        assertEquals("Uploaded · 4.2 MB", status(item(status = UploadStatus.DONE)))
        assertEquals("Already on TOWER · skipped", status(item(status = UploadStatus.DONE, cause = UploadCause.ALREADY_THERE)))
    }

    @Test
    fun `a percentage never rounds up to done`() {
        assertEquals("999 B of 1.0 KB · 99%", status(item(size = 1000, done = 999, status = UploadStatus.RUNNING)))
    }

    @Test
    fun `each failure names its own fix`() {
        fun failed(cause: UploadCause, bytes: Long? = null) = status(item(status = UploadStatus.FAILED, cause = cause, causeBytes = bytes))
        assertEquals("TOWER is full · needs 140 MB more", failed(UploadCause.SHARE_FULL, 140_000_000))
        assertEquals("Lisbon 2026 is read-only for this login", failed(UploadCause.READ_ONLY))
        assertEquals("TOWER refused the password · check it in Settings", failed(UploadCause.SIGN_IN))
        assertEquals("No longer on this phone", failed(UploadCause.SOURCE_GONE))
        assertEquals("Lisbon 2026 isn't on TOWER any more", failed(UploadCause.FOLDER_GONE))
        assertEquals("Couldn't upload", failed(UploadCause.OTHER))
    }

    @Test
    fun `only failures a retry can fix are retryable`() {
        assertTrue(item(status = UploadStatus.FAILED, cause = UploadCause.SHARE_FULL).retryable)
        assertTrue(item(status = UploadStatus.FAILED, cause = UploadCause.CANCELLED).retryable)
        assertFalse(item(status = UploadStatus.FAILED, cause = UploadCause.SOURCE_GONE).retryable)
        assertFalse(item(status = UploadStatus.FAILED, cause = UploadCause.FOLDER_GONE).retryable)
    }

    // ── The section, the summary and the note ──────────────────────────

    @Test
    fun `the section says what the batch is doing`() {
        assertEquals("Uploading · 1 of 2", UploadWording.sectionLabel(listOf(item(status = UploadStatus.RUNNING), item()), "TOWER"))
        assertEquals("Waiting for TOWER", UploadWording.sectionLabel(listOf(item(status = UploadStatus.PAUSED)), "TOWER"))
        assertEquals(
            "Uploaded 1 of 2",
            UploadWording.sectionLabel(listOf(item(status = UploadStatus.DONE), item(status = UploadStatus.FAILED, cause = UploadCause.OTHER)), "TOWER"),
        )
        assertEquals("Just uploaded", UploadWording.sectionLabel(listOf(item(status = UploadStatus.DONE)), "TOWER"))
    }

    @Test
    fun `a finished batch folds into one line by kind`() {
        val batch = listOf(
            item(name = "a.jpg", size = 4_000_000, status = UploadStatus.DONE),
            item(name = "b.heic", size = 3_000_000, status = UploadStatus.DONE),
            item(name = "c.mp4", size = 612_000_000, status = UploadStatus.DONE),
            item(name = "d.jpg", status = UploadStatus.DONE, cause = UploadCause.ALREADY_THERE),
        )
        assertEquals("3 uploaded · 1 skipped", UploadWording.summaryTitle(batch))
        assertEquals("2 photos · 1 video · 619 MB", UploadWording.summaryMeta(batch))
    }

    @Test
    fun `a photo that arrived is not left to vanish`() {
        assertEquals(
            "The photos are below, with this folder's other files.",
            UploadWording.otherFilesNote(listOf(item(name = "a.jpg", status = UploadStatus.DONE), item(name = "b.jpg", status = UploadStatus.DONE))),
        )
        assertEquals(
            "The file is below, with this folder's other files.",
            UploadWording.otherFilesNote(listOf(item(name = "notes.pdf", status = UploadStatus.DONE))),
        )
        assertNull("a video is among the videos, so there is nothing to explain", UploadWording.otherFilesNote(listOf(item(name = "c.mp4", status = UploadStatus.DONE))))
    }

    // ── The tier, the notification and the message ────────────────────

    @Test
    fun `the tier names the folder, or counts files when there is more than one`() {
        val tally = UploadTally(files = 4, position = 2, bytesDone = 1, bytesTotal = 4, folderId = 7, paused = false)
        assertEquals("Uploading 4 to Lisbon 2026 · 2 of 4", UploadWording.tierLine(tally, "TOWER", "Lisbon 2026"))
        assertEquals("Uploading 4 files · 2 of 4", UploadWording.tierLine(tally.copy(folderId = null), "TOWER", null))
        assertEquals("Uploading to Lisbon 2026", UploadWording.tierLine(tally.copy(files = 1, position = 1), "TOWER", "Lisbon 2026"))
        assertEquals("Waiting for TOWER · picks up where it stopped", UploadWording.tierLine(tally.copy(paused = true), "TOWER", "Lisbon 2026"))
    }

    @Test
    fun `the notification says which file`() {
        val tally = UploadTally(files = 4, position = 2, bytesDone = 1, bytesTotal = 4, folderId = 7, paused = false)
        assertEquals("Uploading 4 to Lisbon 2026", UploadWording.notificationTitle(tally, "TOWER", "Lisbon 2026"))
        assertEquals("2 of 4 · b.jpg", UploadWording.notificationText(tally, "b.jpg"))
        assertEquals("Waiting for TOWER", UploadWording.notificationTitle(tally.copy(paused = true), "TOWER", "Lisbon 2026"))
    }

    @Test
    fun `a finished batch says how it went, in one line`() {
        val done = item(status = UploadStatus.DONE)
        val failed = item(status = UploadStatus.FAILED, cause = UploadCause.SHARE_FULL)
        val there = item(status = UploadStatus.DONE, cause = UploadCause.ALREADY_THERE)
        assertEquals(BatchMessage("Uploaded 2 to Lisbon 2026", false), UploadWording.batchMessage(listOf(done, done), "Lisbon 2026"))
        assertEquals(BatchMessage("Uploaded to Lisbon 2026", false), UploadWording.batchMessage(listOf(done), "Lisbon 2026"))
        assertEquals(BatchMessage("1 of 2 didn't upload", true), UploadWording.batchMessage(listOf(done, failed), "Lisbon 2026"))
        assertEquals(BatchMessage("Uploaded 1 to Lisbon 2026 · 1 already there", false), UploadWording.batchMessage(listOf(done, there), "Lisbon 2026"))
        assertEquals(BatchMessage("Already in Lisbon 2026", false), UploadWording.batchMessage(listOf(there), "Lisbon 2026"))
    }

    @Test
    fun `a stop is the user's own doing, and is not reported as a failure`() {
        val done = item(status = UploadStatus.DONE)
        val stopped = item(status = UploadStatus.FAILED, cause = UploadCause.CANCELLED)
        assertEquals(BatchMessage("Stopped · 1 of 2 uploaded", false), UploadWording.batchMessage(listOf(done, stopped), "Lisbon 2026"))
        assertEquals(BatchMessage("Uploads stopped", false), UploadWording.batchMessage(listOf(stopped), "Lisbon 2026"))
    }
}
