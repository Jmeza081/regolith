package com.regolith.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.regolith.data.db.DownloadPickEntity
import com.regolith.data.db.FolderEntity
import com.regolith.data.db.MediaFileEntity
import com.regolith.data.db.RegolithDatabase
import com.regolith.data.db.ServerEntity
import com.regolith.data.db.ShareEntity
import com.regolith.data.db.TransferEntity
import com.regolith.domain.transfer.TransferCause
import com.regolith.domain.transfer.TransferStatus
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The queries the download queue runs between files. The worker asks Room
 * what to do next rather than taking a snapshot at launch, so these are
 * what decide the order a batch copies in and what a Stop leaves behind.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class TransferQueueDaoTest {
    private lateinit var db: RegolithDatabase
    private var shareId = 0L
    private var folderId = 0L

    @Before
    fun setUp() = runTest {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), RegolithDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val serverId = db.serverDao().insert(
            ServerEntity(name = "TOWER", host = "tower", port = 445, authMode = "GUEST", username = null, lastSeenAtMs = null, createdAtMs = 0),
        )
        shareId = db.shareDao().insert(
            ShareEntity(serverId = serverId, name = "media", enabled = true, freeBytes = null, totalBytes = null, lastScanAtMs = null),
        )
        folderId = db.folderDao().upsert(
            FolderEntity(shareId = shareId, parentId = null, relPath = "", name = "media", fileCount = 0, byteCount = 0, lastListedAtMs = null),
        ).id
    }

    @After
    fun tearDown() = db.close()

    private suspend fun file(name: String, size: Long = 1_000): Long =
        db.mediaFileDao().upsert(
            MediaFileEntity(
                shareId = shareId, folderId = folderId, relPath = name, name = name, ext = "mkv",
                sizeBytes = size, modifiedAtMs = 0, durationMs = null, missing = false, addedAtMs = 0, lastSeenAtMs = 0,
            ),
        ).id

    private suspend fun transfer(fileId: Long, status: TransferStatus, created: Long, done: Long = 0, total: Long = 1_000) {
        db.transferDao().insert(
            TransferEntity(
                fileId = fileId, status = status.name, bytesDone = done, totalBytes = total,
                localPath = "$fileId.mkv", cause = null, causeBytes = null,
                createdAtMs = created, updatedAtMs = created, finishedAtMs = null,
            ),
        )
    }

    // ── nextQueued ─────────────────────────────────────────────────────

    @Test
    fun `nextQueued takes the oldest waiting row`() = runTest {
        transfer(file("c.mkv"), TransferStatus.QUEUED, created = 300)
        transfer(file("a.mkv"), TransferStatus.QUEUED, created = 100)
        transfer(file("b.mkv"), TransferStatus.QUEUED, created = 200)
        val next = db.transferDao().nextQueued()
        assertEquals(100L, next?.createdAtMs)
    }

    @Test
    fun `a paused row counts as waiting`() = runTest {
        // The share dropped out mid-copy; it resumes from its .part length
        // like anything else in the queue.
        transfer(file("a.mkv"), TransferStatus.PAUSED, created = 100, done = 400)
        val next = db.transferDao().nextQueued()
        assertNotNull(next)
        assertEquals(400L, next?.bytesDone)
    }

    @Test
    fun `a running row is not handed out again`() = runTest {
        transfer(file("a.mkv"), TransferStatus.RUNNING, created = 100)
        assertNull(db.transferDao().nextQueued())
    }

    @Test
    fun `finished and failed rows are not handed out`() = runTest {
        transfer(file("a.mkv"), TransferStatus.DONE, created = 100)
        transfer(file("b.mkv"), TransferStatus.FAILED, created = 200)
        assertNull(db.transferDao().nextQueued())
    }

    @Test
    fun `an empty queue returns nothing rather than throwing`() = runTest {
        assertNull(db.transferDao().nextQueued())
    }

    // ── activeCount and activeBytes ────────────────────────────────────

    @Test
    fun `activeCount counts what is still owed`() = runTest {
        transfer(file("a.mkv"), TransferStatus.QUEUED, created = 100)
        transfer(file("b.mkv"), TransferStatus.RUNNING, created = 200)
        transfer(file("c.mkv"), TransferStatus.PAUSED, created = 300)
        transfer(file("d.mkv"), TransferStatus.DONE, created = 400)
        transfer(file("e.mkv"), TransferStatus.FAILED, created = 500)
        assertEquals(3, db.transferDao().activeCount())
    }

    @Test
    fun `activeBytes sums only the rows still owed`() = runTest {
        transfer(file("a.mkv"), TransferStatus.RUNNING, created = 100, done = 400, total = 1_000)
        transfer(file("b.mkv"), TransferStatus.QUEUED, created = 200, done = 0, total = 2_000)
        transfer(file("c.mkv"), TransferStatus.DONE, created = 300, done = 9_000, total = 9_000)
        val tally = db.transferDao().activeBytes()
        assertEquals(3_000L, tally.total)
        assertEquals(400L, tally.done)
    }

    @Test
    fun `activeBytes is zero rather than null on an empty queue`() = runTest {
        // COALESCE: a SUM over no rows is NULL in SQL, and a null Long would
        // crash the notification rather than drawing an empty bar.
        val tally = db.transferDao().activeBytes()
        assertEquals(0L, tally.total)
        assertEquals(0L, tally.done)
    }

    // ── cancelActive and requeueRunning ────────────────────────────────

    @Test
    fun `cancelActive fails the pending rows with a cause and leaves the rest`() = runTest {
        val queued = file("a.mkv")
        val running = file("b.mkv")
        val done = file("c.mkv")
        transfer(queued, TransferStatus.QUEUED, created = 100)
        transfer(running, TransferStatus.RUNNING, created = 200, done = 500)
        transfer(done, TransferStatus.DONE, created = 300)

        db.transferDao().cancelActive(now = 999)

        val a = db.transferDao().byFile(queued)!!
        assertEquals(TransferStatus.FAILED.name, a.status)
        assertEquals(TransferCause.CANCELLED.name, a.cause)
        // The bytes already copied stay on the row, so "Try again" resumes.
        val b = db.transferDao().byFile(running)!!
        assertEquals(500L, b.bytesDone)
        // A finished download is untouched by a Stop.
        assertEquals(TransferStatus.DONE.name, db.transferDao().byFile(done)!!.status)
    }

    @Test
    fun `requeueRunning rescues rows a dead process left behind`() = runTest {
        val id = file("a.mkv")
        transfer(id, TransferStatus.RUNNING, created = 100, done = 400)
        db.transferDao().requeueRunning()
        val row = db.transferDao().byFile(id)!!
        assertEquals(TransferStatus.QUEUED.name, row.status)
        assertEquals(400L, row.bytesDone)
    }

    // ── download_picks ─────────────────────────────────────────────────

    private suspend fun pick(folder: Long, path: String, created: Long, discovered: Boolean = false) {
        db.downloadPickDao().insert(
            DownloadPickEntity(folderId = folder, shareId = shareId, relPath = path, discovered = discovered, filesFound = 0, createdAtMs = created),
        )
    }

    private suspend fun folder(path: String): Long =
        db.folderDao().upsert(
            FolderEntity(shareId = shareId, parentId = folderId, relPath = path, name = path, fileCount = 0, byteCount = 0, lastListedAtMs = null),
        ).id

    @Test
    fun `nextUndiscovered walks the picks oldest first`() = runTest {
        pick(folder("Series"), "Series", created = 200)
        pick(folder("Films"), "Films", created = 100)
        assertEquals("Films", db.downloadPickDao().nextUndiscovered()?.relPath)
    }

    @Test
    fun `a walked pick is not walked again`() = runTest {
        val films = folder("Films")
        pick(films, "Films", created = 100)
        db.downloadPickDao().markDiscovered(db.downloadPickDao().nextUndiscovered()!!.id, found = 9)
        assertNull(db.downloadPickDao().nextUndiscovered())
        assertEquals(0, db.downloadPickDao().pendingCount())
    }

    @Test
    fun `picking the same folder twice is one job`() = runTest {
        val films = folder("Films")
        pick(films, "Films", created = 100)
        pick(films, "Films", created = 200)
        // IGNORE on the unique index: tapping Download twice must not walk
        // the same subtree twice.
        assertEquals(1, db.downloadPickDao().pendingCount())
    }

    @Test
    fun `clear empties the queue of picks`() = runTest {
        pick(folder("Films"), "Films", created = 100)
        pick(folder("Series"), "Series", created = 200)
        db.downloadPickDao().clear()
        assertEquals(0, db.downloadPickDao().pendingCount())
    }

    @Test
    fun `a folder that vanished off the share takes its pick with it`() = runTest {
        // CASCADE. This is the real path: a rescan finds the folder gone and
        // prunes it, and a job pointing at a row that no longer exists would
        // make the queue retry forever.
        val films = folder("Films")
        pick(films, "Films", created = 100)
        db.folderDao().deleteChildrenNotIn(folderId, emptyList())
        assertEquals(0, db.downloadPickDao().pendingCount())
    }

    @Test
    fun `a pick carries what to leave out, and an old-shaped pick leaves out nothing`() = runTest {
        val films = folder("Films")
        db.downloadPickDao().insert(
            DownloadPickEntity(
                folderId = films, shareId = shareId, relPath = "Films", createdAtMs = 100,
                excludedFileIds = "10,11", excludedPaths = "Films/Extras\nFilms/Trailers",
            ),
        )
        val pick = db.downloadPickDao().nextUndiscovered()!!
        assertEquals("10,11", pick.excludedFileIds)
        assertEquals("Films/Extras\nFilms/Trailers", pick.excludedPaths)

        // The worker's own parse, so the two agree on the separators.
        assertEquals(setOf(10L, 11L), pick.excludedFileIds.split(',').mapNotNull { it.trim().toLongOrNull() }.toSet())
        assertEquals(setOf("Films/Extras", "Films/Trailers"), pick.excludedPaths.split('\n').filter { it.isNotEmpty() }.toSet())

        // Defaults: a pick made without exclusions parses to nothing to skip.
        db.downloadPickDao().clear()
        pick(folder("Series"), "Series", created = 200)
        val plain = db.downloadPickDao().nextUndiscovered()!!
        assertEquals(emptySet<Long>(), plain.excludedFileIds.split(',').mapNotNull { it.trim().toLongOrNull() }.toSet())
        assertEquals(emptySet<String>(), plain.excludedPaths.split('\n').filter { it.isNotEmpty() }.toSet())
    }
}
