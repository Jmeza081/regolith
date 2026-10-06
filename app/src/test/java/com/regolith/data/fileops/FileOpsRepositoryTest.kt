package com.regolith.data.fileops

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.regolith.data.db.FolderEntity
import com.regolith.data.db.MediaFileEntity
import com.regolith.data.db.RegolithDatabase
import com.regolith.data.db.ServerEntity
import com.regolith.data.db.ShareEntity
import com.regolith.data.db.ShareFileEntity
import com.regolith.data.transfer.DownloadStore
import com.regolith.domain.fileops.FileOpError
import com.regolith.domain.fileops.FileOpTarget
import com.regolith.domain.smb.ServerAccess
import com.regolith.domain.smb.SmbCredentials
import com.regolith.domain.smb.SmbHost
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

    /** The folders whose pictures the repository asked to have checked again. */
    private val picturesChecked = mutableListOf<Long>()

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
            object : ServerAccess {
                override suspend fun credentialsFor(serverId: Long): SmbCredentials = SmbCredentials.Guest
                override suspend fun hostFor(serverId: Long) = SmbHost("tower", 445)
            },
            db.transferDao(),
            DownloadStore(ApplicationProvider.getApplicationContext()),
            db.subtreeDao(),
            db.shareFileDao(),
            { folderId, _ -> picturesChecked += folderId },
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

    /** A file that is not a video, on the share and in Browse's rows, the way a listing would leave it. */
    private suspend fun other(name: String, folderId: Long = filmsId, folderPath: String = "Films"): Long {
        val relPath = if (folderPath.isEmpty()) name else "$folderPath/$name"
        gateway.addFile("media", relPath, ByteArray(10))
        return db.shareFileDao().insert(
            ShareFileEntity(shareId = shareId, folderId = folderId, relPath = relPath, name = name, sizeBytes = 10, modifiedAtMs = 1),
        )
    }

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
    fun `every companion is renamed to match, and its row keeps its id`() = runTest {
        val id = film("beach.mp4")
        val srt = other("beach.en.srt")
        other("beach.jpg")
        other("beach.nfo")
        // Not beach's: another video's subtitles, and the folder's own poster.
        film("sunset.mp4")
        other("sunset.srt")
        other("poster.jpg")

        assertTrue(repo.rename(file(id), "Hawaii day one").ok)

        for (name in listOf("Hawaii day one.mp4", "Hawaii day one.en.srt", "Hawaii day one.jpg", "Hawaii day one.nfo")) {
            assertTrue("$name is not on the share", onShare("Films/$name"))
        }
        for (name in listOf("beach.en.srt", "beach.jpg", "beach.nfo")) assertFalse("$name was left behind", onShare("Films/$name"))
        assertTrue(onShare("Films/sunset.srt"))
        assertTrue(onShare("Films/poster.jpg"))
        val row = db.shareFileDao().byId(srt)!!
        assertEquals("Hawaii day one.en.srt", row.name)
        assertEquals("Films/Hawaii day one.en.srt", row.relPath)
    }

    @Test
    fun `a companion's new name already taken stops the rename before anything moves`() = runTest {
        val id = film("beach.mp4")
        other("beach.en.srt")
        // Left over from something else, and it must not be overwritten.
        other("sunset.en.srt")

        val result = repo.rename(file(id), "sunset")

        val failure = result.failures.single()
        assertEquals(FileOpError.COMPANION_TAKEN, failure.error)
        assertEquals("sunset.en.srt", failure.detail)
        assertTrue(onShare("Films/beach.mp4"))
        assertTrue(onShare("Films/beach.en.srt"))
        assertEquals("beach.mp4", db.mediaFileDao().byId(id)!!.name)
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
    fun `a video's companions move with it, rows and all`() = runTest {
        val id = film("beach.mp4")
        val srt = other("beach.en.srt")
        other("beach.chapters.txt")
        other("poster.jpg")

        assertTrue(repo.move(listOf(file(id)), archiveId).ok)

        assertTrue(onShare("Archive/beach.mp4"))
        assertTrue(onShare("Archive/beach.en.srt"))
        assertTrue(onShare("Archive/beach.chapters.txt"))
        assertTrue("the folder's poster went with a video", onShare("Films/poster.jpg"))
        val row = db.shareFileDao().byId(srt)!!
        assertEquals(archiveId, row.folderId)
        assertEquals("Archive/beach.en.srt", row.relPath)
        assertEquals(listOf("poster.jpg"), db.shareFileDao().inFolder(filmsId).map { it.name })
    }

    @Test
    fun `a companion's name taken in the destination holds back only its video`() = runTest {
        val beach = film("beach.mp4")
        other("beach.en.srt")
        val sunset = film("sunset.mp4")
        other("beach.en.srt", folderId = archiveId, folderPath = "Archive")

        val result = repo.move(listOf(file(beach), file(sunset)), archiveId)

        assertEquals(listOf(file(sunset)), result.done)
        val failure = result.failures.single()
        assertEquals(FileOpError.COMPANION_TAKEN, failure.error)
        assertEquals("beach.en.srt", failure.detail)
        assertTrue("the video moved without its subtitles", onShare("Films/beach.mp4"))
        assertTrue(onShare("Films/beach.en.srt"))
    }

    @Test
    fun `two videos sharing a base name take their shared companions when both go`() = runTest {
        val mp4 = film("beach.mp4")
        val mkv = film("beach.mkv")
        other("beach.en.srt")

        assertTrue(repo.move(listOf(file(mp4), file(mkv)), archiveId).ok)

        assertTrue(onShare("Archive/beach.en.srt"))
        assertFalse(onShare("Films/beach.en.srt"))
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
    fun `delete takes every companion and its row, and nothing else`() = runTest {
        val id = film("beach.mp4")
        other("beach.en.srt")
        other("beach.jpg")
        val poster = other("poster.jpg")
        film("beach.2019.mp4")
        other("beach.2019.srt")

        assertTrue(repo.delete(listOf(file(id))).ok)

        assertFalse(onShare("Films/beach.en.srt"))
        assertFalse(onShare("Films/beach.jpg"))
        assertTrue("another video's subtitles went", onShare("Films/beach.2019.srt"))
        assertTrue("the folder's poster went", onShare("Films/poster.jpg"))
        assertEquals(listOf("beach.2019.srt", "poster.jpg"), db.shareFileDao().inFolder(filmsId).map { it.name })
        assertNotNull(db.shareFileDao().byId(poster))
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

    // ── files that are not videos ──────────────────────────────────────
    //
    // Browse lists every file, and these can be picked too. One goes alone:
    // no companions, nothing in Room but its own row.

    private fun otherTarget(id: Long) = FileOpTarget.other(id)

    @Test
    fun `a file that is not a video renames keeping its extension, and only itself`() = runTest {
        film("beach.mp4")
        val srt = other("beach.en.srt")

        assertTrue(repo.rename(otherTarget(srt), "beach.fr").ok)

        assertTrue(onShare("Films/beach.fr.srt"))
        assertTrue("the video went with its subtitles", onShare("Films/beach.mp4"))
        val row = db.shareFileDao().byId(srt)!!
        assertEquals("beach.fr.srt", row.name)
        assertEquals("Films/beach.fr.srt", row.relPath)
        assertTrue("subtitles are no picture to check", picturesChecked.isEmpty())
    }

    @Test
    fun `renaming a folder's poster has the folder's pictures checked again`() = runTest {
        val poster = other("poster.jpg")

        assertTrue(repo.rename(otherTarget(poster), "old poster").ok)

        assertTrue(onShare("Films/old poster.jpg"))
        assertEquals(listOf(filmsId), picturesChecked)
    }

    @Test
    fun `a file that is not a video moves alone, row and all`() = runTest {
        film("beach.mp4")
        val poster = other("poster.jpg")

        assertTrue(repo.move(listOf(otherTarget(poster)), archiveId).ok)

        assertTrue(onShare("Archive/poster.jpg"))
        assertTrue(onShare("Films/beach.mp4"))
        assertEquals(archiveId, db.shareFileDao().byId(poster)!!.folderId)
        assertEquals("both folders' pictures are looked at again", setOf(filmsId, archiveId), picturesChecked.toSet())
    }

    @Test
    fun `a companion picked along with its video is moved once, not failed`() = runTest {
        val beach = film("beach.mp4")
        val srt = other("beach.en.srt")

        val result = repo.move(listOf(file(beach), otherTarget(srt)), archiveId)

        assertTrue(result.failures.toString(), result.ok)
        assertEquals(2, result.done.size)
        assertTrue(onShare("Archive/beach.en.srt"))
    }

    @Test
    fun `deleting a file that is not a video takes it and its row, and nothing else`() = runTest {
        film("beach.mp4")
        val poster = other("poster.jpg")
        val srt = other("beach.en.srt")

        assertTrue(repo.delete(listOf(otherTarget(poster))).ok)

        assertFalse(onShare("Films/poster.jpg"))
        assertNull(db.shareFileDao().byId(poster))
        assertNotNull("a sibling went with it", db.shareFileDao().byId(srt))
        assertTrue(onShare("Films/beach.mp4"))
        assertEquals(listOf(filmsId), picturesChecked)
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
