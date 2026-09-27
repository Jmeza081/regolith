package com.regolith.data.transfer

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.regolith.data.db.FolderEntity
import com.regolith.data.db.RegolithDatabase
import com.regolith.data.db.ServerEntity
import com.regolith.data.db.ShareEntity
import com.regolith.domain.media.DeviceSource
import com.regolith.domain.smb.ServerAccess
import com.regolith.domain.smb.SmbCredentials
import com.regolith.domain.smb.SmbHost
import com.regolith.domain.transfer.ConflictPolicy
import com.regolith.domain.transfer.UploadCause
import com.regolith.domain.transfer.UploadStatus
import com.regolith.testing.FakePhoneFiles
import com.regolith.testing.FakeSmbGateway
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The queue from the app's side (P16): turning a pick into rows, asking
 * about clashes once, and what Try again, Cancel and Clear leave behind —
 * on the share and in the phone's grants.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class UploadRepositoryTest {
    private lateinit var db: RegolithDatabase
    private lateinit var repo: UploadRepository
    private val gateway = FakeSmbGateway()
    private val phone = FakePhoneFiles()
    private val scheduler = object : UploadScheduler {
        var enqueued = 0
        var now = 0
        override fun enqueue() {
            enqueued++
        }

        override suspend fun enqueueNow() {
            now++
        }
    }
    private var folderId = 0L
    private val dir = "Home videos/Lisbon 2026"

    @Before
    fun setUp() = runTest {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), RegolithDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        folderId = destination(host = "tower", folderName = "Lisbon 2026", relPath = dir)
        gateway.addDir("media", dir)
        val access = object : ServerAccess {
            override suspend fun credentialsFor(serverId: Long): SmbCredentials = SmbCredentials.Guest
            override suspend fun hostFor(serverId: Long) = SmbHost("tower")
        }
        repo = UploadRepository(
            ApplicationProvider.getApplicationContext(), db.uploadDao(), db.folderDao(), db.shareDao(), db.serverDao(),
            access, gateway, phone, scheduler,
        )
    }

    @After
    fun tearDown() = db.close()

    private suspend fun destination(host: String, folderName: String, relPath: String): Long {
        val serverId = db.serverDao().insert(
            ServerEntity(name = host.uppercase(), host = host, port = 445, authMode = "GUEST", username = null, lastSeenAtMs = null, createdAtMs = 0),
        )
        val shareId = db.shareDao().insert(
            ShareEntity(serverId = serverId, name = "media", enabled = true, freeBytes = null, totalBytes = null, lastScanAtMs = null),
        )
        return db.folderDao().upsert(
            FolderEntity(shareId = shareId, parentId = null, relPath = relPath, name = folderName, fileCount = 0, byteCount = 0, lastListedAtMs = null),
        ).id
    }

    private fun pick(name: String, size: Int): String {
        val uri = "content://phone/$name"
        phone.add(uri, name, ByteArray(size))
        return uri
    }

    @Test
    fun `phone storage and the demo library are nowhere to upload to`() = runTest {
        val phoneFolder = destination(host = DeviceSource.HOST, folderName = "Camera", relPath = "DCIM/Camera")
        assertNull(repo.destinationOf(phoneFolder))
        assertNull(repo.prepare(phoneFolder, listOf(pick("a.jpg", 10))))
        assertNotNull(repo.destinationOf(folderId))
    }

    @Test
    fun `a pick keeps its access before anything else, so the queue can read it later`() = runTest {
        val uris = listOf(pick("a.jpg", 10), pick("b.jpg", 20))
        val prepared = repo.prepare(folderId, uris)!!
        assertEquals(uris.toSet(), phone.held)
        assertEquals(listOf("a.jpg", "b.jpg"), prepared.files.map { it.name })
        assertFalse(prepared.needsAnswer)
    }

    @Test
    fun `a pick that cannot be read is counted, and its access handed back`() = runTest {
        val prepared = repo.prepare(folderId, listOf(pick("a.jpg", 10), "content://phone/vanished.jpg"))!!
        assertEquals(1, prepared.unreadable)
        assertEquals(setOf("content://phone/a.jpg"), phone.held)
    }

    @Test
    fun `one listing finds the clashes, and only a different file needs asking about`() = runTest {
        gateway.addFile("media", "$dir/same.jpg", ByteArray(10))
        gateway.addFile("media", "$dir/other.mp4", ByteArray(99))
        val prepared = repo.prepare(folderId, listOf(pick("same.jpg", 10), pick("other.mp4", 50), pick("new.jpg", 5)))!!

        assertEquals(listOf("same.jpg", "other.mp4"), prepared.clashes.map { it.file.name })
        assertTrue(prepared.clashes.first { it.file.name == "same.jpg" }.sameFile)
        assertTrue(prepared.needsAnswer)
    }

    @Test
    fun `a share out of reach finds no clashes rather than stopping the pick`() = runTest {
        gateway.reachable = false
        val prepared = repo.prepare(folderId, listOf(pick("a.jpg", 10)))!!
        assertTrue(prepared.clashes.isEmpty())
        assertEquals(1, prepared.files.size)
    }

    @Test
    fun `queueing a pick writes one batch and starts the queue`() = runTest {
        val prepared = repo.prepare(folderId, listOf(pick("a.jpg", 10), pick("b.jpg", 20)))!!
        assertEquals(2, repo.enqueue(folderId, prepared.files, ConflictPolicy.REPLACE))

        val rows = db.uploadDao().forFolder(folderId)
        assertEquals(1, rows.map { it.batchId }.distinct().size)
        assertTrue(rows.all { it.status == UploadStatus.QUEUED.name && it.conflictPolicy == ConflictPolicy.REPLACE.name })
        assertEquals(1, scheduler.enqueued)
    }

    @Test
    fun `try again puts a failed file back and asks for the queue now`() = runTest {
        val prepared = repo.prepare(folderId, listOf(pick("a.jpg", 10)))!!
        repo.enqueue(folderId, prepared.files, ConflictPolicy.KEEP_BOTH)
        val row = db.uploadDao().forFolder(folderId).single()
        db.uploadDao().update(row.copy(status = UploadStatus.FAILED.name, cause = UploadCause.SHARE_FULL.name, causeBytes = 5))

        repo.retry(row.id)
        val again = db.uploadDao().byId(row.id)!!
        assertEquals(UploadStatus.QUEUED.name, again.status)
        assertNull(again.cause)
        assertEquals(1, scheduler.now)
    }

    @Test
    fun `try again over a section skips what trying again cannot fix`() = runTest {
        val prepared = repo.prepare(folderId, listOf(pick("a.jpg", 10), pick("b.jpg", 20)))!!
        repo.enqueue(folderId, prepared.files, ConflictPolicy.KEEP_BOTH)
        val (a, b) = db.uploadDao().forFolder(folderId)
        db.uploadDao().update(a.copy(status = UploadStatus.FAILED.name, cause = UploadCause.SHARE_FULL.name))
        db.uploadDao().update(b.copy(status = UploadStatus.FAILED.name, cause = UploadCause.SOURCE_GONE.name))

        repo.retryAll(folderId)
        assertEquals(UploadStatus.QUEUED.name, db.uploadDao().byId(a.id)!!.status)
        assertEquals(UploadStatus.FAILED.name, db.uploadDao().byId(b.id)!!.status)
    }

    @Test
    fun `cancelling a waiting file takes its part off the share and hands its access back`() = runTest {
        val prepared = repo.prepare(folderId, listOf(pick("a.jpg", 10)))!!
        repo.enqueue(folderId, prepared.files, ConflictPolicy.KEEP_BOTH)
        val row = db.uploadDao().forFolder(folderId).single()
        // An earlier attempt got some of it there before the share dropped.
        db.uploadDao().update(row.copy(status = UploadStatus.PAUSED.name, bytesDone = 4))
        gateway.addFile("media", "$dir/a.jpg.part", ByteArray(4))

        repo.cancel(row.id)
        assertNull(db.uploadDao().byId(row.id))
        assertNull(gateway.files.getValue("media")["$dir/a.jpg.part"])
        assertTrue(phone.held.isEmpty())
    }

    @Test
    fun `clear takes the finished rows and leaves what is still going`() = runTest {
        val prepared = repo.prepare(folderId, listOf(pick("a.jpg", 10), pick("b.jpg", 20)))!!
        repo.enqueue(folderId, prepared.files, ConflictPolicy.KEEP_BOTH)
        val (a, b) = db.uploadDao().forFolder(folderId)
        db.uploadDao().update(a.copy(status = UploadStatus.DONE.name))

        repo.clearFinished(folderId)
        assertEquals(listOf(b.id), db.uploadDao().forFolder(folderId).map { it.id })
        assertEquals("the finished one's access went with it", setOf(b.sourceUri), phone.held)
    }

    @Test
    fun `at startup a dead run's files are queued again and day-old ones cleared`() = runTest {
        val prepared = repo.prepare(folderId, listOf(pick("a.jpg", 10), pick("b.jpg", 20)))!!
        repo.enqueue(folderId, prepared.files, ConflictPolicy.KEEP_BOTH)
        val (a, b) = db.uploadDao().forFolder(folderId)
        db.uploadDao().update(a.copy(status = UploadStatus.RUNNING.name))
        db.uploadDao().update(b.copy(status = UploadStatus.DONE.name, finishedAtMs = 1))
        phone.held += "content://phone/orphan.jpg"

        repo.resumeInterrupted()
        assertEquals(UploadStatus.QUEUED.name, db.uploadDao().byId(a.id)!!.status)
        assertNull(db.uploadDao().byId(b.id))
        assertEquals("grants no row needs are handed back", setOf(a.sourceUri), phone.held)
        assertEquals(2, scheduler.enqueued)
    }

    @Test
    fun `a folder's rows are observed as items with the names they will have`() = runTest {
        val prepared = repo.prepare(folderId, listOf(pick("a.jpg", 10)))!!
        repo.enqueue(folderId, prepared.files, ConflictPolicy.KEEP_BOTH)
        val item = repo.observeFolder(folderId).first().single()
        assertEquals("a.jpg", item.name)
        assertEquals(UploadStatus.QUEUED, item.status)
        assertTrue(item.live)
    }
}
