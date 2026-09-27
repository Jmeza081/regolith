package com.regolith.data.transfer

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.regolith.data.db.FolderEntity
import com.regolith.data.db.RegolithDatabase
import com.regolith.data.db.ServerEntity
import com.regolith.data.db.ShareEntity
import com.regolith.data.db.UploadEntity
import com.regolith.domain.smb.ServerAccess
import com.regolith.domain.smb.SmbCredentials
import com.regolith.domain.smb.SmbHost
import com.regolith.domain.transfer.ConflictPolicy
import com.regolith.domain.transfer.UploadCause
import com.regolith.domain.transfer.UploadStatus
import com.regolith.testing.FakePhoneFiles
import com.regolith.testing.FakeSmbGateway
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
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
 * Every way one upload can end (P16), against a fake share and a fake
 * phone. The questions that matter are the ones a real NAS makes hard to
 * stage: a Wi-Fi drop in the middle of a file, a share filling up, a
 * password that stopped working, a photo deleted from the gallery while it
 * waits its turn.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class UploadRunnerTest {
    private lateinit var db: RegolithDatabase
    private lateinit var runner: UploadRunner
    private val gateway = FakeSmbGateway()
    private val phone = FakePhoneFiles()
    private var serverId = 0L
    private var folderId = 0L
    private val dir = "Home videos/Lisbon 2026"

    private val access = object : ServerAccess {
        override suspend fun credentialsFor(serverId: Long): SmbCredentials = SmbCredentials.Guest
        override suspend fun hostFor(serverId: Long) = SmbHost("tower")
    }

    @Before
    fun setUp() = runTest {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), RegolithDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        serverId = db.serverDao().insert(
            ServerEntity(name = "TOWER", host = "tower", port = 445, authMode = "GUEST", username = null, lastSeenAtMs = null, createdAtMs = 0),
        )
        val shareId = db.shareDao().insert(
            ShareEntity(serverId = serverId, name = "media", enabled = true, freeBytes = null, totalBytes = null, lastScanAtMs = null),
        )
        val root = db.folderDao().upsert(folder(shareId, null, "", "media"))
        val parent = db.folderDao().upsert(folder(shareId, root.id, "Home videos", "Home videos"))
        folderId = db.folderDao().upsert(folder(shareId, parent.id, dir, "Lisbon 2026")).id
        gateway.addDir("media", dir)
        gateway.missingFoldersAreNotFound = true
        runner = UploadRunner(db.uploadDao(), db.folderDao(), db.shareDao(), db.serverDao(), access, gateway, phone)
            // Every write re-reads its row, so a cancel lands mid-file without the test waiting.
            .apply { progressIntervalMs = 0 }
    }

    @After
    fun tearDown() = db.close()

    private fun folder(shareId: Long, parentId: Long?, relPath: String, name: String) =
        FolderEntity(shareId = shareId, parentId = parentId, relPath = relPath, name = name, fileCount = 0, byteCount = 0, lastListedAtMs = null)

    /** Distinct, non-repeating-looking content, so a byte in the wrong place cannot pass by accident. */
    private fun bytes(size: Int, seed: Int = 1) = ByteArray(size) { ((it * 31 + seed) % 251).toByte() }

    private suspend fun queue(
        name: String,
        content: ByteArray?,
        policy: ConflictPolicy = ConflictPolicy.KEEP_BOTH,
        size: Long? = null,
        modified: Long? = null,
    ): UploadEntity {
        val uri = "content://phone/$name"
        if (content != null) phone.add(uri, name, content, modified)
        val id = db.uploadDao().insertAll(
            listOf(
                UploadEntity(
                    batchId = 1, folderId = folderId, sourceUri = uri, targetName = name,
                    sizeBytes = size ?: content?.size?.toLong() ?: 1_000, sourceModifiedAtMs = modified,
                    conflictPolicy = policy.name, status = UploadStatus.QUEUED.name, cause = null, causeBytes = null,
                    bytesDone = 0, createdAtMs = 0, updatedAtMs = 0, finishedAtMs = null,
                ),
            ),
        ).single()
        return db.uploadDao().byId(id)!!
    }

    private suspend fun row(id: Long) = db.uploadDao().byId(id)!!

    private fun onShare(name: String): ByteArray? = gateway.files.getValue("media")["$dir/$name"]

    // ── Arriving ────────────────────────────────────────────────────────

    @Test
    fun `a photo arrives under its own name, whole, and nothing is left half-written`() = runTest {
        val photo = bytes(3 * MiB + 17)
        val queued = queue("20260914_183022.jpg", photo)

        val outcome = runner.send(queued)

        assertEquals(UploadRunner.Outcome.Done(folderId, video = false), outcome)
        assertArrayEquals(photo, onShare("20260914_183022.jpg"))
        assertNull("the .part is renamed, not left beside it", onShare("20260914_183022.jpg.part"))
        val done = row(queued.id)
        assertEquals(UploadStatus.DONE.name, done.status)
        assertEquals(photo.size.toLong(), done.bytesDone)
        assertNotNull(done.finishedAtMs)
    }

    @Test
    fun `a video that lands asks for its folder to be listed again`() = runTest {
        val outcome = runner.send(queue("20260914_190455.mp4", bytes(2 * MiB)))
        assertEquals(UploadRunner.Outcome.Done(folderId, video = true), outcome)
    }

    @Test
    fun `the phone's date travels with the file`() = runTest {
        runner.send(queue("a.jpg", bytes(1_000), modified = 1_600_000_000_000))
        assertEquals(1_600_000_000_000, gateway.mtime("media", "$dir/a.jpg"))
    }

    // ── The share dropping out ─────────────────────────────────────────

    @Test
    fun `the share dropping mid-file pauses it, and the retry sends only what had not arrived`() = runTest {
        val video = bytes(5 * MiB)
        val queued = queue("clip.mp4", video)
        gateway.dropAfterAppendedBytes = 3L * MiB + 100

        assertEquals(UploadRunner.Outcome.Paused, runner.send(queued))
        val paused = row(queued.id)
        assertEquals(UploadStatus.PAUSED.name, paused.status)
        assertEquals(UploadCause.SHARE_DROPPED.name, paused.cause)
        assertNotNull("the server is marked out of reach", db.serverDao().byId(serverId)!!.unreachableSinceMs)
        assertEquals(3L * MiB + 100, onShare("clip.mp4.part")!!.size.toLong())

        assertEquals(UploadRunner.Outcome.Done(folderId, video = true), runner.send(paused))
        assertArrayEquals(video, onShare("clip.mp4"))
        assertEquals("every byte crossed the wire exactly once", video.size.toLong(), gateway.appendedBytes)
        assertNull("reachable again once it answered", db.serverDao().byId(serverId)!!.unreachableSinceMs)
    }

    // ── No room ─────────────────────────────────────────────────────────

    @Test
    fun `a share without room fails in a second, before a byte is sent`() = runTest {
        gateway.freeSpace = 1_000
        val queued = queue("big.jpg", bytes(5_000))

        assertEquals(UploadRunner.Outcome.Failed, runner.send(queued))
        val failed = row(queued.id)
        assertEquals(UploadCause.SHARE_FULL.name, failed.cause)
        assertEquals(4_000L, failed.causeBytes)
        assertEquals("nothing was opened for writing", 0, gateway.appendOpens)
    }

    @Test
    fun `a share that fills up mid-file fails that file, keeping what arrived for later`() = runTest {
        gateway.freeSpace = null // a server that will not say
        gateway.capacityBytes = 2L * MiB + MiB / 2
        val queued = queue("clip.mp4", bytes(4 * MiB))

        assertEquals(UploadRunner.Outcome.Failed, runner.send(queued))
        val failed = row(queued.id)
        assertEquals(UploadStatus.FAILED.name, failed.status)
        assertEquals(UploadCause.SHARE_FULL.name, failed.cause)
        assertEquals(2L * MiB, failed.causeBytes)
        assertEquals("the .part stays for Try again", 2L * MiB, onShare("clip.mp4.part")!!.size.toLong())
    }

    // ── Refusals ────────────────────────────────────────────────────────

    @Test
    fun `a read-only folder fails the file and everything else waiting for that folder`() = runTest {
        gateway.readOnly = true
        val first = queue("a.jpg", bytes(1_000))
        val second = queue("b.jpg", bytes(1_000))

        assertEquals(UploadRunner.Outcome.Failed, runner.send(first))
        assertEquals(UploadCause.READ_ONLY.name, row(first.id).cause)
        assertEquals("the same answer is coming for the next one", UploadCause.READ_ONLY.name, row(second.id).cause)
        assertEquals(UploadStatus.FAILED.name, row(second.id).status)
    }

    @Test
    fun `a password that stopped working fails everything waiting on that server`() = runTest {
        gateway.acceptedCredentials = SmbCredentials.Password("jesse", "new password")
        val first = queue("a.jpg", bytes(1_000))
        val second = queue("b.jpg", bytes(1_000))

        assertEquals(UploadRunner.Outcome.Failed, runner.send(first))
        assertEquals(UploadCause.SIGN_IN.name, row(first.id).cause)
        assertEquals(UploadCause.SIGN_IN.name, row(second.id).cause)
    }

    @Test
    fun `a folder gone from the share is said to be gone, for every file going there`() = runTest {
        gateway.dirs.getValue("media").remove(dir)
        val first = queue("a.jpg", bytes(1_000))
        val second = queue("b.jpg", bytes(1_000))

        assertEquals(UploadRunner.Outcome.Failed, runner.send(first))
        assertEquals(UploadCause.FOLDER_GONE.name, row(first.id).cause)
        assertEquals(UploadCause.FOLDER_GONE.name, row(second.id).cause)
    }

    // ── The phone's side ────────────────────────────────────────────────

    @Test
    fun `a file deleted from the phone fails as gone, and leaves nothing on the share`() = runTest {
        val queued = queue("deleted.jpg", content = null)

        assertEquals(UploadRunner.Outcome.Failed, runner.send(queued))
        assertEquals(UploadCause.SOURCE_GONE.name, row(queued.id).cause)
        assertNull(onShare("deleted.jpg.part"))
    }

    @Test
    fun `a photo edited after it was picked is sent again from the start, once`() = runTest {
        // A .part from before the edit, longer than the photo is now.
        gateway.addFile("media", "$dir/a.jpg.part", bytes(3_000, seed = 9))
        val edited = bytes(2_000, seed = 5)
        val queued = queue("a.jpg", edited, size = 5_000)

        assertEquals(UploadRunner.Outcome.Done(folderId, video = false), runner.send(queued))
        assertArrayEquals(edited, onShare("a.jpg"))
        assertEquals(2_000L, row(queued.id).sizeBytes)
    }

    @Test
    fun `a part bigger than the file belongs to something else and is thrown away`() = runTest {
        gateway.addFile("media", "$dir/a.jpg.part", bytes(5_000, seed = 9))
        val photo = bytes(3_000)

        runner.send(queue("a.jpg", photo))
        assertArrayEquals(photo, onShare("a.jpg"))
    }

    // ── Names already taken ─────────────────────────────────────────────

    @Test
    fun `the same name and size is the same file, skipped without a byte sent`() = runTest {
        val photo = bytes(4_000)
        gateway.addFile("media", "$dir/a.jpg", photo)
        val queued = queue("a.jpg", photo)

        assertEquals(UploadRunner.Outcome.Skipped, runner.send(queued))
        assertEquals(UploadStatus.DONE.name, row(queued.id).status)
        assertEquals(UploadCause.ALREADY_THERE.name, row(queued.id).cause)
        assertEquals(0, gateway.appendOpens)
    }

    @Test
    fun `keeping both lands the upload as (1) and leaves the other file alone`() = runTest {
        val theirs = bytes(1_234, seed = 7)
        gateway.addFile("media", "$dir/a.jpg", theirs)
        val ours = bytes(4_000)
        val queued = queue("a.jpg", ours, ConflictPolicy.KEEP_BOTH)

        runner.send(queued)
        assertArrayEquals(theirs, onShare("a.jpg"))
        assertArrayEquals(ours, onShare("a (1).jpg"))
        assertEquals("the row names where it went", "a (1).jpg", row(queued.id).targetName)
    }

    @Test
    fun `replace takes the name, and only because the user chose it`() = runTest {
        gateway.addFile("media", "$dir/a.jpg", bytes(1_234, seed = 7))
        val ours = bytes(4_000)
        runner.send(queue("a.jpg", ours, ConflictPolicy.REPLACE))
        assertArrayEquals(ours, onShare("a.jpg"))
    }

    @Test
    fun `replace still works on a server that will not rename over a name`() = runTest {
        gateway.renameOverExistingFails = true
        gateway.addFile("media", "$dir/a.jpg", bytes(1_234, seed = 7))
        val ours = bytes(4_000)
        runner.send(queue("a.jpg", ours, ConflictPolicy.REPLACE))
        assertArrayEquals(ours, onShare("a.jpg"))
    }

    @Test
    fun `skip leaves the other file alone and sends nothing`() = runTest {
        val theirs = bytes(1_234, seed = 7)
        gateway.addFile("media", "$dir/a.jpg", theirs)
        val queued = queue("a.jpg", bytes(4_000), ConflictPolicy.SKIP)

        assertEquals(UploadRunner.Outcome.Skipped, runner.send(queued))
        assertEquals(UploadCause.SKIPPED.name, row(queued.id).cause)
        assertArrayEquals(theirs, onShare("a.jpg"))
    }

    @Test
    fun `a folder with the file's name is never replaced, whatever the policy`() = runTest {
        gateway.addDir("media", "$dir/a.jpg")
        runner.send(queue("a.jpg", bytes(1_000), ConflictPolicy.REPLACE))
        assertTrue(gateway.isDir("media", "$dir/a.jpg"))
        assertNotNull(onShare("a (1).jpg"))
    }

    // ── Cancel ──────────────────────────────────────────────────────────

    @Test
    fun `cancelling mid-file stops the send and takes the half-file away`() = runTest {
        val queued = queue("clip.mp4", bytes(5 * MiB))
        phone.onReadAfter = 2L * MiB
        phone.onRead = { runBlocking { db.uploadDao().deleteIds(listOf(queued.id)) } }

        assertEquals(UploadRunner.Outcome.Cancelled, runner.send(queued))
        assertNull(onShare("clip.mp4.part"))
        assertNull(onShare("clip.mp4"))
        assertFalse("stopped well before the end", gateway.appendedBytes >= 5L * MiB)
    }

    private companion object {
        const val MiB = 1 shl 20
    }
}
