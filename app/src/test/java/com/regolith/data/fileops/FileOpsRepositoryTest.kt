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
import com.regolith.domain.fileops.FileOpTarget
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
        gateway.addShare("media")
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
            db.subtreeDao(),
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

    /** A folder on the share and in Room, the way a listing would leave it. */
    private suspend fun folder(name: String, parentId: Long, parentPath: String = ""): Long {
        val relPath = if (parentPath.isEmpty()) name else "$parentPath/$name"
        gateway.addDir("media", relPath)
        return db.folderDao().upsert(
            FolderEntity(
                shareId = shareId, parentId = parentId, relPath = relPath, name = name,
                fileCount = 0, byteCount = 0, lastListedAtMs = 1,
            ),
        ).id
    }

    private fun file(id: Long) = FileOpTarget.file(id)
    private fun dir(id: Long) = FileOpTarget.folder(id)

    // ── rename ─────────────────────────────────────────────────────────

    @Test
    fun `rename moves the file on the share and keeps the row id`() = runTest {
        val id = film("Heat.1995.mkv")
        val result = repo.rename(file(id), "Heat (1995)")
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
        val result = repo.rename(file(id), "Sicario.2015")
        assertEquals(FileOpError.NAME_TAKEN, result.failures.single().error)
        assertTrue("the original was moved anyway", onShare("Films/Heat.1995.mkv"))
        assertEquals(100, gateway.files["media"]!!["Films/Sicario.2015.mkv"]!!.size)
    }

    @Test
    fun `a name a share cannot take is refused before anything is sent`() = runTest {
        val id = film("Heat.1995.mkv")
        val result = repo.rename(file(id), "Films/Heat")
        assertEquals(FileOpError.BAD_NAME, result.failures.single().error)
        assertTrue(onShare("Films/Heat.1995.mkv"))
    }

    @Test
    fun `the chapter file follows the film`() = runTest {
        val id = film("Heat.1995.mkv")
        gateway.addFile("media", "Films/Heat.1995.chapters.txt", "0:00 Opening".toByteArray())
        assertTrue(repo.rename(file(id), "Heat (1995)").ok)
        assertTrue("chapters left behind", onShare("Films/Heat (1995).chapters.txt"))
        assertFalse(onShare("Films/Heat.1995.chapters.txt"))
    }

    @Test
    fun `a film with no chapter file renames fine`() = runTest {
        val id = film("Heat.1995.mkv")
        assertTrue(repo.rename(file(id), "Heat (1995)").ok)
        assertTrue(onShare("Films/Heat (1995).mkv"))
    }

    // ── move ───────────────────────────────────────────────────────────

    @Test
    fun `move puts the file in the destination and keeps its id`() = runTest {
        val id = film("Heat.1995.mkv")
        val result = repo.move(listOf(file(id)), archiveId)
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
        val result = repo.move(listOf(file(heat), file(sicario)), archiveId)
        assertEquals(listOf(file(heat)), result.done)
        assertEquals(FileOpError.NAME_TAKEN, result.failures.single().error)
        assertTrue("the file that was already there was destroyed", gateway.files["media"]!!["Archive/Sicario.2015.mkv"]!!.size == 999)
        assertTrue("the clashing file was moved anyway", onShare("Films/Sicario.2015.mkv"))
    }

    @Test
    fun `the share dropping mid-batch leaves every file either moved or not`() = runTest {
        val ids = (1..4).map { film("Film$it.mkv") }
        gateway.unreachableAfterRenames = 2
        val result = repo.move(ids.map(::file), archiveId)

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
        val result = repo.move(listOf(file(id)), filmsId)
        assertTrue(result.ok)
        assertTrue(onShare("Films/Heat.1995.mkv"))
    }

    @Test
    fun `folder counts follow the files`() = runTest {
        val id = film("Heat.1995.mkv", size = 100)
        assertTrue(repo.move(listOf(file(id)), archiveId).ok)
        assertEquals(0, db.folderDao().byId(filmsId)!!.fileCount)
        assertEquals(1, db.folderDao().byId(archiveId)!!.fileCount)
        assertEquals(100, db.folderDao().byId(archiveId)!!.byteCount)
    }

    // ── delete ─────────────────────────────────────────────────────────

    @Test
    fun `delete removes the file, its chapter file and its row`() = runTest {
        val id = film("Heat.1995.mkv")
        gateway.addFile("media", "Films/Heat.1995.chapters.txt", "0:00 Opening".toByteArray())
        val result = repo.delete(listOf(file(id)))
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
        val result = repo.delete(listOf(file(id)))
        assertEquals(FileOpError.FORBIDDEN, result.failures.single().error)
        assertTrue(onShare("Films/Heat.1995.mkv"))
        assertNotNull("the row was dropped for a delete that never happened", db.mediaFileDao().byId(id))
    }

    @Test
    fun `deleting several keeps going and reports each one`() = runTest {
        val ids = (1..3).map { film("Film$it.mkv") }
        val result = repo.delete(ids.map(::file))
        assertEquals(3, result.done.size)
        assertTrue(result.ok)
        for (id in ids) assertNull(db.mediaFileDao().byId(id))
        assertEquals(0, db.folderDao().byId(filmsId)!!.fileCount)
    }

    // ── folders ────────────────────────────────────────────────────────
    //
    // On the share a folder is one rename like any other. The work these
    // cover is LOCAL: every row underneath carries a relPath that the move
    // has just made wrong, and nothing else recomputes them.

    @Test
    fun `renaming a folder rewrites every path underneath it`() = runTest {
        val season = folder("Season 01", filmsId, "Films")
        val inner = folder("Extras", season, "Films/Season 01")
        val episode = film("E01.mkv", folderId = season, folderPath = "Films/Season 01")
        val extra = film("Blooper.mkv", folderId = inner, folderPath = "Films/Season 01/Extras")

        val result = repo.rename(dir(season), "Season One")
        assertTrue(result.failures.toString(), result.ok)

        assertTrue("the folder did not move on the share", onShare("Films/Season One/E01.mkv"))
        assertTrue("the subtree did not follow", onShare("Films/Season One/Extras/Blooper.mkv"))
        // Ids survive, which is what keeps chapters and resume points attached.
        assertEquals("Films/Season One", db.folderDao().byId(season)!!.relPath)
        assertEquals("Season One", db.folderDao().byId(season)!!.name)
        assertEquals("Films/Season One/Extras", db.folderDao().byId(inner)!!.relPath)
        assertEquals("Films/Season One/E01.mkv", db.mediaFileDao().byId(episode)!!.relPath)
        assertEquals("Films/Season One/Extras/Blooper.mkv", db.mediaFileDao().byId(extra)!!.relPath)
    }

    @Test
    fun `moving a folder reparents it and rewrites the subtree`() = runTest {
        val season = folder("Season 01", filmsId, "Films")
        val episode = film("E01.mkv", folderId = season, folderPath = "Films/Season 01")

        assertTrue(repo.move(listOf(dir(season)), archiveId).ok)

        assertTrue(onShare("Archive/Season 01/E01.mkv"))
        assertFalse(onShare("Films/Season 01/E01.mkv"))
        val row = db.folderDao().byId(season)!!
        assertEquals(archiveId, row.parentId)
        assertEquals("Archive/Season 01", row.relPath)
        assertEquals("Archive/Season 01/E01.mkv", db.mediaFileDao().byId(episode)!!.relPath)
    }

    @Test
    fun `a folder cannot be moved into itself or into its own subtree`() = runTest {
        val season = folder("Season 01", filmsId, "Films")
        val inner = folder("Extras", season, "Films/Season 01")

        assertEquals(FileOpError.BAD_DESTINATION, repo.move(listOf(dir(season)), season).failures.single().error)
        assertEquals(FileOpError.BAD_DESTINATION, repo.move(listOf(dir(season)), inner).failures.single().error)
        // And nothing moved while being refused.
        assertEquals("Films/Season 01", db.folderDao().byId(season)!!.relPath)
    }

    @Test
    fun `a share root is never a target`() = runTest {
        val root = db.folderDao().byPath(shareId, "")!!
        assertEquals(FileOpError.BAD_DESTINATION, repo.rename(dir(root.id), "Nope").failures.single().error)
        assertEquals(FileOpError.BAD_DESTINATION, repo.delete(listOf(dir(root.id))).failures.single().error)
        assertEquals(FileOpError.BAD_DESTINATION, repo.move(listOf(dir(root.id)), filmsId).failures.single().error)
    }

    @Test
    fun `a folder and a file move together in one batch`() = runTest {
        val season = folder("Season 01", filmsId, "Films")
        film("E01.mkv", folderId = season, folderPath = "Films/Season 01")
        val loose = film("Heat.1995.mkv")

        val result = repo.move(listOf(dir(season), file(loose)), archiveId)
        assertTrue(result.failures.toString(), result.ok)
        assertEquals(2, result.done.size)
        assertTrue(onShare("Archive/Season 01/E01.mkv"))
        assertTrue(onShare("Archive/Heat.1995.mkv"))
    }

    @Test
    fun `a folder whose name is taken in the destination is refused, not merged`() = runTest {
        val season = folder("Season 01", filmsId, "Films")
        film("E01.mkv", folderId = season, folderPath = "Films/Season 01")
        // Something already called Season 01 is sitting in Archive.
        folder("Season 01", archiveId, "Archive")

        val result = repo.move(listOf(dir(season)), archiveId)
        assertEquals(FileOpError.NAME_TAKEN, result.failures.single().error)
        assertTrue("the source subtree was moved anyway", onShare("Films/Season 01/E01.mkv"))
    }

    @Test
    fun `deleting a folder takes the whole subtree, on the share and in Room`() = runTest {
        val season = folder("Season 01", filmsId, "Films")
        val inner = folder("Extras", season, "Films/Season 01")
        val episode = film("E01.mkv", folderId = season, folderPath = "Films/Season 01")
        val extra = film("Blooper.mkv", folderId = inner, folderPath = "Films/Season 01/Extras")
        // A file the app never listed — a subtitle — goes too. That is the
        // server's recursive delete, and the dialog has to promise it.
        gateway.addFile("media", "Films/Season 01/E01.srt", ByteArray(4))

        val result = repo.delete(listOf(dir(season)))
        assertTrue(result.failures.toString(), result.ok)

        assertFalse(onShare("Films/Season 01/E01.mkv"))
        assertFalse(onShare("Films/Season 01/Extras/Blooper.mkv"))
        assertFalse("an unlisted file survived", onShare("Films/Season 01/E01.srt"))
        assertNull(db.folderDao().byId(season))
        assertNull("a subfolder row survived — parentId has no cascade", db.folderDao().byId(inner))
        // Files cascade away with their folder row.
        assertNull(db.mediaFileDao().byId(episode))
        assertNull(db.mediaFileDao().byId(extra))
    }

    @Test
    fun `a read-only share refuses a folder delete and leaves the subtree alone`() = runTest {
        val season = folder("Season 01", filmsId, "Films")
        val episode = film("E01.mkv", folderId = season, folderPath = "Films/Season 01")
        gateway.readOnly = true

        val result = repo.delete(listOf(dir(season)))
        assertEquals(FileOpError.FORBIDDEN, result.failures.single().error)
        assertTrue(onShare("Films/Season 01/E01.mkv"))
        assertNotNull(db.folderDao().byId(season))
        assertNotNull(db.mediaFileDao().byId(episode))
    }

    // ── making a folder ────────────────────────────────────────────────

    @Test
    fun `createFolder makes it on the share and returns a row that can be moved into`() = runTest {
        val result = repo.createFolder(archiveId, "  Season 02  ")
        assertTrue(result.failures.toString(), result.ok)
        val made = result.done.single()
        assertTrue("a created folder is a folder", made.isFolder)

        val row = db.folderDao().byId(made.id)!!
        // Trimmed, parented, and marked listed: we know it is empty.
        assertEquals("Season 02", row.name)
        assertEquals("Archive/Season 02", row.relPath)
        assertEquals(archiveId, row.parentId)
        assertNotNull("a folder we just made is not 'not listed yet'", row.lastListedAtMs)
        assertTrue(gateway.isDir("media", "Archive/Season 02"))

        // And it is a real destination straight away.
        val heat = film("Heat.1995.mkv")
        assertTrue(repo.move(listOf(file(heat)), made.id).ok)
        assertTrue(onShare("Archive/Season 02/Heat.1995.mkv"))
    }

    @Test
    fun `createFolder refuses a name that is taken or a name a share cannot take`() = runTest {
        folder("Season 02", archiveId, "Archive")
        assertEquals(FileOpError.NAME_TAKEN, repo.createFolder(archiveId, "season 02").failures.single().error)
        assertEquals(FileOpError.BAD_NAME, repo.createFolder(archiveId, "  ").failures.single().error)
        assertEquals(FileOpError.BAD_NAME, repo.createFolder(archiveId, "a/b").failures.single().error)
    }
}
