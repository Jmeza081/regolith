package com.regolith.data.fileops

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.regolith.data.db.FolderEntity
import com.regolith.data.db.MediaFileEntity
import com.regolith.data.db.RegolithDatabase
import com.regolith.data.db.ServerEntity
import com.regolith.data.db.ShareEntity
import com.regolith.data.transfer.DownloadStore
import com.regolith.domain.fileops.FileOpError
import com.regolith.domain.smb.CredentialSource
import com.regolith.domain.smb.SmbCredentials
import com.regolith.testing.FakeSmbGateway
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
 * What the SMB probe proved, held to by the code that uses it: a batch is
 * N independent operations, each file ends up either changed or not, and a
 * row keeps its id — and therefore its chapters and its resume point —
 * across a rename or a move.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class FileOpsRepositoryTest {
    private lateinit var db: RegolithDatabase
    private lateinit var gateway: FakeSmbGateway
    private lateinit var repo: FileOpsRepository
    private var shareId = 0L
    private var filmsId = 0L
    private var archiveId = 0L

    @Before
    fun setUp() = runTest {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), RegolithDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        gateway = FakeSmbGateway()
        val serverId = db.serverDao().insert(
            ServerEntity(name = "TOWER", host = "tower", port = 445, authMode = "GUEST", username = null, lastSeenAtMs = null, createdAtMs = 0),
        )
        shareId = db.shareDao().insert(ShareEntity(serverId = serverId, name = "media", enabled = true, freeBytes = null, totalBytes = null, lastScanAtMs = null))
        val root = db.folderDao().upsert(FolderEntity(shareId = shareId, parentId = null, relPath = "", name = "media", fileCount = 0, byteCount = 0, lastListedAtMs = null))
        filmsId = db.folderDao().upsert(FolderEntity(shareId = shareId, parentId = root.id, relPath = "Films", name = "Films", fileCount = 0, byteCount = 0, lastListedAtMs = 1)).id
        archiveId = db.folderDao().upsert(FolderEntity(shareId = shareId, parentId = root.id, relPath = "Archive", name = "Archive", fileCount = 0, byteCount = 0, lastListedAtMs = 1)).id
        repo = FileOpsRepository(
            gateway, db.serverDao(), db.shareDao(), db.folderDao(), db.mediaFileDao(),
            object : CredentialSource {
                override suspend fun credentialsFor(serverId: Long): SmbCredentials = SmbCredentials.Guest
            },
            db.transferDao(),
            DownloadStore(ApplicationProvider.getApplicationContext()),
        )
    }

    @After
    fun tearDown() = db.close()

    /** A film in Films, on the share and in Room, the way a listing would leave it. */
    private suspend fun film(name: String, folderId: Long = filmsId, folderPath: String = "Films", size: Long = 100): Long {
        val relPath = if (folderPath.isEmpty()) name else "$folderPath/$name"
        gateway.addFile("media", relPath, ByteArray(size.toInt()))
        return db.mediaFileDao().upsert(
            MediaFileEntity(
                shareId = shareId, folderId = folderId, relPath = relPath, name = name,
                ext = name.substringAfterLast('.', ""), sizeBytes = size, modifiedAtMs = 1, durationMs = null,
                missing = false, addedAtMs = 1, lastSeenAtMs = 1,
            ),
        ).id
    }

    private fun onShare(relPath: String) = gateway.files["media"]?.containsKey(relPath) == true

    // ── rename ─────────────────────────────────────────────────────────

    @Test
    fun `rename moves the file on the share and keeps the row id`() = runTest {
        val id = film("Heat.1995.mkv")
        val result = repo.rename(id, "Heat (1995)")
        assertTrue(result.ok)
        assertTrue("new name not on the share", onShare("Films/Heat (1995).mkv"))
        assertFalse("old name still on the share", onShare("Films/Heat.1995.mkv"))
        val row = db.mediaFileDao().byId(id)!!
        // The id is the whole point: chapters and progress hang off it.
        assertEquals("Heat (1995).mkv", row.name)
        assertEquals("Films/Heat (1995).mkv", row.relPath)
        assertEquals("mkv", row.ext)
    }

    @Test
    fun `rename refuses a name that is taken, and touches nothing`() = runTest {
        val id = film("Heat.1995.mkv")
        film("Sicario.2015.mkv")
        val result = repo.rename(id, "Sicario.2015")
        assertEquals(FileOpError.NAME_TAKEN, result.failures.single().error)
        assertTrue("the original was moved anyway", onShare("Films/Heat.1995.mkv"))
        assertEquals(100, gateway.files["media"]!!["Films/Sicario.2015.mkv"]!!.size)
    }

    @Test
    fun `a name a share cannot take is refused before anything is sent`() = runTest {
        val id = film("Heat.1995.mkv")
        val result = repo.rename(id, "Films/Heat")
        assertEquals(FileOpError.BAD_NAME, result.failures.single().error)
        assertTrue(onShare("Films/Heat.1995.mkv"))
    }

    @Test
    fun `the chapter file follows the film`() = runTest {
        val id = film("Heat.1995.mkv")
        gateway.addFile("media", "Films/Heat.1995.chapters.txt", "0:00 Opening".toByteArray())
        assertTrue(repo.rename(id, "Heat (1995)").ok)
        assertTrue("chapters left behind", onShare("Films/Heat (1995).chapters.txt"))
        assertFalse(onShare("Films/Heat.1995.chapters.txt"))
    }

    @Test
    fun `a film with no chapter file renames fine`() = runTest {
        val id = film("Heat.1995.mkv")
        assertTrue(repo.rename(id, "Heat (1995)").ok)
        assertTrue(onShare("Films/Heat (1995).mkv"))
    }

    // ── move ───────────────────────────────────────────────────────────

    @Test
    fun `move puts the file in the destination and keeps its id`() = runTest {
        val id = film("Heat.1995.mkv")
        val result = repo.move(listOf(id), archiveId)
        assertTrue(result.ok)
        assertTrue(onShare("Archive/Heat.1995.mkv"))
        val row = db.mediaFileDao().byId(id)!!
        assertEquals(archiveId, row.folderId)
        assertEquals("Archive/Heat.1995.mkv", row.relPath)
    }

    @Test
    fun `a clash in the destination fails only that file`() = runTest {
        val heat = film("Heat.1995.mkv")
        val sicario = film("Sicario.2015.mkv")
        // Something already called Sicario.2015.mkv is sitting in Archive.
        film("Sicario.2015.mkv", folderId = archiveId, folderPath = "Archive", size = 999)
        val result = repo.move(listOf(heat, sicario), archiveId)
        assertEquals(listOf(heat), result.done)
        assertEquals(FileOpError.NAME_TAKEN, result.failures.single().error)
        assertTrue("the file that was already there was destroyed", gateway.files["media"]!!["Archive/Sicario.2015.mkv"]!!.size == 999)
        assertTrue("the clashing file was moved anyway", onShare("Films/Sicario.2015.mkv"))
    }

    @Test
    fun `the share dropping mid-batch leaves every file either moved or not`() = runTest {
        val ids = (1..4).map { film("Film$it.mkv") }
        gateway.unreachableAfterRenames = 2
        val result = repo.move(ids, archiveId)

        assertEquals(2, result.done.size)
        assertEquals(2, result.failures.size)
        assertTrue("should read as a dropped share", result.dropped)
        assertTrue("should read as partial", result.partial)
        // The invariant the probe established: nothing is in both places, and
        // nothing is in neither.
        for (id in ids) {
            val row = db.mediaFileDao().byId(id)!!
            val inArchive = onShare("Archive/${row.name}")
            val inFilms = onShare("Films/${row.name}")
            assertTrue("${row.name} is in neither place", inArchive || inFilms)
            assertFalse("${row.name} is in both places", inArchive && inFilms)
            // And Room agrees with the share about where it is.
            assertEquals(if (inArchive) "Archive/${row.name}" else "Films/${row.name}", row.relPath)
        }
    }

    @Test
    fun `moving into the folder it is already in is a no-op, not a failure`() = runTest {
        val id = film("Heat.1995.mkv")
        val result = repo.move(listOf(id), filmsId)
        assertTrue(result.ok)
        assertTrue(onShare("Films/Heat.1995.mkv"))
    }

    @Test
    fun `folder counts follow the files`() = runTest {
        val id = film("Heat.1995.mkv", size = 100)
        assertTrue(repo.move(listOf(id), archiveId).ok)
        assertEquals(0, db.folderDao().byId(filmsId)!!.fileCount)
        assertEquals(1, db.folderDao().byId(archiveId)!!.fileCount)
        assertEquals(100, db.folderDao().byId(archiveId)!!.byteCount)
    }

    // ── delete ─────────────────────────────────────────────────────────

    @Test
    fun `delete removes the file, its chapter file and its row`() = runTest {
        val id = film("Heat.1995.mkv")
        gateway.addFile("media", "Films/Heat.1995.chapters.txt", "0:00 Opening".toByteArray())
        val result = repo.delete(listOf(id))
        assertTrue(result.ok)
        assertFalse(onShare("Films/Heat.1995.mkv"))
        assertFalse("chapter file left behind", onShare("Films/Heat.1995.chapters.txt"))
        // The row goes, unlike a rescan, which would only mark it missing.
        assertNull(db.mediaFileDao().byId(id))
    }

    @Test
    fun `a read-only share fails the delete and leaves the file alone`() = runTest {
        val id = film("Heat.1995.mkv")
        gateway.readOnly = true
        val result = repo.delete(listOf(id))
        assertEquals(FileOpError.FORBIDDEN, result.failures.single().error)
        assertTrue(onShare("Films/Heat.1995.mkv"))
        assertNotNull("the row was dropped for a delete that never happened", db.mediaFileDao().byId(id))
    }

    @Test
    fun `deleting several keeps going and reports each one`() = runTest {
        val ids = (1..3).map { film("Film$it.mkv") }
        val result = repo.delete(ids)
        assertEquals(3, result.done.size)
        assertTrue(result.ok)
        for (id in ids) assertNull(db.mediaFileDao().byId(id))
        assertEquals(0, db.folderDao().byId(filmsId)!!.fileCount)
    }
}
