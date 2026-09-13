package com.regolith.data.media

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.regolith.data.db.FolderEntity
import com.regolith.data.db.MediaFileEntity
import com.regolith.data.db.RegolithDatabase
import com.regolith.data.db.ServerEntity
import com.regolith.data.db.ShareEntity
import com.regolith.data.db.TransferEntity
import com.regolith.data.db.UserChapterEntity
import com.regolith.data.transfer.DownloadStore
import com.regolith.domain.playback.ChapterSyncNote
import com.regolith.domain.playback.ChapterSyncState
import com.regolith.domain.smb.CredentialSource
import com.regolith.domain.smb.SmbCredentials
import com.regolith.domain.smb.SmbHost
import com.regolith.testing.FakeSmbGateway
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** P10: the sidecar on the share is the durable copy; the rows are its cache. Newest wins, whole. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class ChapterSyncRepositoryTest {
    private lateinit var db: RegolithDatabase
    private val gateway = FakeSmbGateway()
    private val host = SmbHost("tower")
    private var enqueued = 0
    private lateinit var repo: ChapterSyncRepository
    private var shareId = 0L
    private var folderId = 0L
    private var heat = 0L
    private val store = DownloadStore(ApplicationProvider.getApplicationContext())

    @Before
    fun setUp() = runTest {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), RegolithDatabase::class.java).allowMainThreadQueries().build()
        val serverId = db.serverDao().insert(ServerEntity(name = "TOWER", host = "tower", port = 445, authMode = "GUEST", username = null, lastSeenAtMs = null, createdAtMs = 0))
        shareId = db.shareDao().insert(ShareEntity(serverId = serverId, name = "media", enabled = true, freeBytes = null, totalBytes = null, lastScanAtMs = null))
        val root = db.folderDao().upsert(FolderEntity(shareId = shareId, parentId = null, relPath = "", name = "media", fileCount = 0, byteCount = 0, lastListedAtMs = null)).id
        folderId = db.folderDao().upsert(FolderEntity(shareId = shareId, parentId = root, relPath = "Films", name = "Films", fileCount = 0, byteCount = 0, lastListedAtMs = null)).id
        heat = db.mediaFileDao().upsert(
            MediaFileEntity(
                shareId = shareId, folderId = folderId, relPath = "Films/Heat.1995.mkv", name = "Heat.1995.mkv", ext = "mkv",
                sizeBytes = 1, modifiedAtMs = 1, durationMs = 3 * 3600_000L, missing = false, addedAtMs = 1, lastSeenAtMs = 1,
            ),
        ).id
        gateway.addFile("media", "Films/Heat.1995.mkv", ByteArray(1))
        repo = ChapterSyncRepository(
            syncDao = db.chapterSyncDao(), chapterDao = db.userChapterDao(), mediaFileDao = db.mediaFileDao(), shareDao = db.shareDao(), serverDao = db.serverDao(),
            credentials = object : CredentialSource { override suspend fun credentialsFor(serverId: Long) = SmbCredentials.Guest },
            writer = SidecarWriter(gateway),
            scheduler = object : ChapterSyncScheduler { override fun enqueue() { enqueued++ } },
            transfers = db.transferDao(),
            store = store,
        )
    }

    @After
    fun tearDown() = db.close()

    private suspend fun listFilms() = repo.onFolderListed(folderId, "Films", gateway.list(host, SmbCredentials.Guest, "media", "Films"), host, SmbCredentials.Guest, "media")
    private suspend fun rows() = db.userChapterDao().forFile(heat)
    private suspend fun state() = repo.observe(heat).first()
    private fun localRows(vararg titles: Pair<Long, String?>, at: Long) = titles.map { (ms, t) -> UserChapterEntity(fileId = heat, startMs = ms, title = t, updatedAtMs = at) }
    private val sidecar = "CHAPTER01=00:00:00.000\nCHAPTER01NAME=Intro\nCHAPTER02=00:12:30.000\nCHAPTER02NAME=The heist\n"

    @Test
    fun `a file on the share is imported, and again only when it changes`() = runTest {
        gateway.addFile("media", "Films/Heat.1995.chapters.txt", sidecar.toByteArray(), modifiedAtMs = 5_000)
        listFilms()
        assertEquals(listOf("Intro", "The heist"), rows().map { it.title })
        assertEquals(ChapterSyncState.FROM_SHARE, state().state)
        val opens = gateway.openCount
        listFilms()
        assertEquals(opens, gateway.openCount) // same modified time: not read again
        gateway.addFile("media", "Films/Heat.1995.chapters.txt", sidecar.replace("The heist", "The job").toByteArray(), modifiedAtMs = 6_000)
        listFilms()
        assertEquals("The job", rows()[1].title)
    }

    @Test
    fun `an edit here is written to the share by the worker`() = runTest {
        db.userChapterDao().replaceForFile(heat, localRows(0L to "Intro", 750_000L to "The heist", at = 10_000))
        repo.markDirty(heat)
        assertEquals(1, enqueued)
        assertEquals(ChapterSyncState.WAITING, state().state)
        assertTrue(repo.syncDirty())
        assertEquals(sidecar, String(gateway.files.getValue("media").getValue("Films/Heat.1995.chapters.txt")))
        assertEquals(ChapterSyncState.ON_SHARE, state().state)
        assertFalse(db.chapterSyncDao().byFile(heat)!!.dirty)
    }

    @Test
    fun `a read-only share keeps the rows here and says so`() = runTest {
        gateway.readOnly = true
        db.userChapterDao().replaceForFile(heat, localRows(0L to "Intro", at = 10_000))
        repo.markDirty(heat)
        assertTrue(repo.syncDirty())
        assertEquals(ChapterSyncState.READ_ONLY, state().state)
        assertNull(state().note)
        assertEquals(1, rows().size)
        // Writing turned off for the share reads as phone-only, not as waiting.
        gateway.readOnly = false
        db.shareDao().setWriteChapters(shareId, false)
        assertEquals(ChapterSyncState.PHONE_ONLY, state().state)
        assertTrue(repo.syncDirty())
        assertFalse(gateway.files.getValue("media").containsKey("Films/Heat.1995.chapters.txt"))
    }

    @Test
    fun `both changed - the newer one wins whole`() = runTest {
        // Share newer than the phone's edit: the share replaces, with a note.
        db.userChapterDao().replaceForFile(heat, localRows(0L to "Mine", at = 1_000))
        repo.markDirty(heat)
        gateway.addFile("media", "Films/Heat.1995.chapters.txt", sidecar.toByteArray(), modifiedAtMs = 2_000)
        listFilms()
        assertEquals(listOf("Intro", "The heist"), rows().map { it.title })
        assertEquals(ChapterSyncNote.REPLACED_BY_SHARE, state().note)
        assertFalse(db.chapterSyncDao().byFile(heat)!!.dirty)
        // Phone newer than the share's file: the phone keeps its rows and the worker writes over.
        db.userChapterDao().replaceForFile(heat, localRows(0L to "Mine again", at = 9_000))
        repo.markDirty(heat)
        gateway.addFile("media", "Films/Heat.1995.chapters.txt", sidecar.toByteArray(), modifiedAtMs = 3_000)
        listFilms()
        assertEquals(listOf("Mine again"), rows().map { it.title })
        assertTrue(repo.syncDirty())
        assertTrue(String(gateway.files.getValue("media").getValue("Films/Heat.1995.chapters.txt")).contains("Mine again"))
    }

    @Test
    fun `a file that vanished takes its rows with it, unless the phone has unsent edits`() = runTest {
        gateway.addFile("media", "Films/Heat.1995.chapters.txt", sidecar.toByteArray(), modifiedAtMs = 5_000)
        listFilms()
        gateway.files.getValue("media").remove("Films/Heat.1995.chapters.txt")
        listFilms()
        assertEquals(0, rows().size)
        assertNull(state().state)
        db.userChapterDao().replaceForFile(heat, localRows(0L to "Mine", at = 10_000))
        repo.markDirty(heat)
        listFilms()
        assertEquals(1, rows().size)
    }

    @Test
    fun `revert deletes the file - clear leaves it`() = runTest {
        db.userChapterDao().replaceForFile(heat, localRows(0L to "Intro", at = 10_000))
        repo.markDirty(heat)
        repo.syncDirty()
        repo.revert(heat)
        assertEquals(0, rows().size)
        assertFalse(gateway.files.getValue("media").containsKey("Films/Heat.1995.chapters.txt"))
        assertNull(db.chapterSyncDao().byFile(heat))

        db.userChapterDao().replaceForFile(heat, localRows(0L to "Intro", at = 11_000))
        repo.markDirty(heat)
        repo.syncDirty()
        repo.clearLocal()
        assertEquals(0, rows().size)
        assertTrue(gateway.files.getValue("media").containsKey("Films/Heat.1995.chapters.txt"))
        listFilms()
        assertEquals(1, rows().size) // and the scan brings them back from the file
    }

    @Test
    fun `a downloaded film keeps a chapter file beside its copy, written at once and cleared with the cache`() = runTest {
        db.transferDao().insert(
            TransferEntity(
                fileId = heat, status = "DONE", bytesDone = 1, totalBytes = 1, localPath = "$heat.mkv", cause = null, causeBytes = null,
                createdAtMs = 1, updatedAtMs = 1, finishedAtMs = 1,
            ),
        )
        val local = store.sidecarFor("$heat.mkv")
        gateway.readOnly = true // the share cannot take it, the phone still has it
        db.userChapterDao().replaceForFile(heat, localRows(0L to "Offline edit", at = 10_000))
        repo.markDirty(heat)
        assertTrue(local.exists())
        assertTrue(local.readText().contains("Offline edit"))
        // Downloading brings the share's file along when the phone has nothing yet.
        db.userChapterDao().deleteForFile(heat); db.chapterSyncDao().delete(heat); local.delete()
        gateway.readOnly = false
        gateway.addFile("media", "Films/Heat.1995.chapters.txt", sidecar.toByteArray(), modifiedAtMs = 5_000)
        repo.onDownloaded(heat, host, SmbCredentials.Guest, "media", "Films", "Heat.1995.mkv")
        assertEquals(listOf("Intro", "The heist"), rows().map { it.title })
        assertEquals(sidecar, local.readText())
        repo.clearLocal()
        assertFalse(local.exists())
        assertEquals(0, rows().size)
    }

    @Test
    fun `revert on a read-only share drops the rows, and warns the file stays`() = runTest {
        db.userChapterDao().replaceForFile(heat, localRows(0L to "Intro", at = 10_000))
        repo.markDirty(heat)
        repo.syncDirty()
        gateway.readOnly = true
        repo.revert(heat)
        assertEquals(0, rows().size)
        assertEquals(ChapterSyncNote.FILE_STAYS, state().note)
        assertTrue(gateway.files.getValue("media").containsKey("Films/Heat.1995.chapters.txt"))
    }
}
