package com.regolith.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.regolith.data.db.FolderEntity
import com.regolith.data.db.RegolithDatabase
import com.regolith.data.db.ServerEntity
import com.regolith.data.db.ShareEntity
import com.regolith.data.db.ShareFileEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The files in a folder that are not videos, as Browse lists them: what a
 * listing leaves behind, and what a folder rename or move does to them.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class ShareFileDaoTest {
    private lateinit var db: RegolithDatabase
    private var shareId = 0L
    private var rootId = 0L
    private var filmsId = 0L

    @Before
    fun setUp() = runTest {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), RegolithDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val serverId = db.serverDao().insert(ServerEntity(name = "TOWER", host = "tower", port = 445, authMode = "GUEST", username = null, lastSeenAtMs = null, createdAtMs = 0))
        shareId = db.shareDao().insert(ShareEntity(serverId = serverId, name = "media", enabled = true, freeBytes = null, totalBytes = null, lastScanAtMs = null))
        rootId = db.folderDao().upsert(FolderEntity(shareId = shareId, parentId = null, relPath = "", name = "media", fileCount = 0, byteCount = 0, lastListedAtMs = null)).id
        filmsId = db.folderDao().upsert(FolderEntity(shareId = shareId, parentId = rootId, relPath = "Films", name = "Films", fileCount = 0, byteCount = 0, lastListedAtMs = null)).id
    }

    @After
    fun tearDown() = db.close()

    private fun other(relPath: String, folderId: Long = filmsId, size: Long = 10) = ShareFileEntity(
        shareId = shareId, folderId = folderId, relPath = relPath, name = relPath.substringAfterLast('/'),
        sizeBytes = size, modifiedAtMs = 1,
    )

    private suspend fun names(folderId: Long = filmsId) = db.shareFileDao().inFolder(folderId).map { it.name }

    @Test
    fun `a listing keeps the rows it finds, by id, and drops the ones it does not`() = runTest {
        val dao = db.shareFileDao()
        dao.replaceInFolder(filmsId, listOf(other("Films/poster.jpg"), other("Films/beach.en.srt")))
        val posterId = dao.byPath(shareId, "Films/poster.jpg")!!.id

        dao.replaceInFolder(filmsId, listOf(other("Films/poster.jpg", size = 20), other("Films/beach.nfo")))

        assertEquals(listOf("beach.nfo", "poster.jpg"), names())
        val poster = dao.byPath(shareId, "Films/poster.jpg")!!
        assertEquals("a file still there keeps its row", posterId, poster.id)
        assertEquals(20L, poster.sizeBytes)
    }

    @Test
    fun `a listing keeps a picture's measurements while the file is the same, and drops them when it changed`() = runTest {
        val dao = db.shareFileDao()
        dao.replaceInFolder(filmsId, listOf(other("Films/IMG_1.jpg", size = 100), other("Films/IMG_2.jpg", size = 200)))
        val one = dao.byPath(shareId, "Films/IMG_1.jpg")!!
        val two = dao.byPath(shareId, "Films/IMG_2.jpg")!!
        dao.saveFacts(one.id, one.sizeBytes, one.modifiedAtMs, width = 3024, height = 4032, takenAtMs = 5, camera = "Pixel 8", measuredAtMs = 9)
        dao.saveFacts(two.id, two.sizeBytes, two.modifiedAtMs, width = 4032, height = 3024, takenAtMs = 6, camera = null, measuredAtMs = 9)

        // The same first picture; the second replaced on the share under the same name.
        dao.replaceInFolder(filmsId, listOf(other("Films/IMG_1.jpg", size = 100), other("Films/IMG_2.jpg", size = 250)))

        val kept = dao.byPath(shareId, "Films/IMG_1.jpg")!!
        assertEquals(3024, kept.width)
        assertEquals("Pixel 8", kept.camera)
        assertEquals(9L, kept.measuredAtMs)
        val changed = dao.byPath(shareId, "Films/IMG_2.jpg")!!
        assertEquals("the same row", two.id, changed.id)
        assertEquals("measured again", null, changed.measuredAtMs)
        assertEquals(null, changed.width)
        assertEquals(listOf(changed.id), dao.unmeasuredInFolder(filmsId).map { it.id })
    }

    @Test
    fun `a measurement of a file that changed meanwhile is not written`() = runTest {
        val dao = db.shareFileDao()
        dao.replaceInFolder(filmsId, listOf(other("Films/IMG_1.jpg", size = 100)))
        val before = dao.byPath(shareId, "Films/IMG_1.jpg")!!
        dao.replaceInFolder(filmsId, listOf(other("Films/IMG_1.jpg", size = 120)))

        // Read from the file as it was: its size no longer matches.
        dao.saveFacts(before.id, before.sizeBytes, before.modifiedAtMs, width = 10, height = 10, takenAtMs = null, camera = null, measuredAtMs = 9)

        assertEquals(null, dao.byPath(shareId, "Films/IMG_1.jpg")!!.measuredAtMs)
    }

    @Test
    fun `an empty listing empties the folder, and only that folder`() = runTest {
        val dao = db.shareFileDao()
        dao.replaceInFolder(rootId, listOf(other("notes.txt", folderId = rootId)))
        dao.replaceInFolder(filmsId, listOf(other("Films/poster.jpg")))

        dao.replaceInFolder(filmsId, emptyList())

        assertEquals(emptyList<String>(), names())
        assertEquals(listOf("notes.txt"), names(rootId))
    }

    @Test
    fun `a folder renamed or moved takes its files' paths along`() = runTest {
        val kidsId = db.folderDao().upsert(
            FolderEntity(shareId = shareId, parentId = filmsId, relPath = "Films/Kids", name = "Kids", fileCount = 0, byteCount = 0, lastListedAtMs = null),
        ).id
        db.shareFileDao().replaceInFolder(filmsId, listOf(other("Films/poster.jpg")))
        db.shareFileDao().replaceInFolder(kidsId, listOf(other("Films/Kids/poster.gif", folderId = kidsId)))
        // A sibling whose name merely starts the same must not be caught.
        db.shareFileDao().replaceInFolder(rootId, listOf(other("Films 2.txt", folderId = rootId)))

        db.subtreeDao().relocate(filmsId, shareId, "Films", "Movies", "Movies", rootId)

        assertEquals("Movies/poster.jpg", db.shareFileDao().inFolder(filmsId).single().relPath)
        assertEquals("Movies/Kids/poster.gif", db.shareFileDao().inFolder(kidsId).single().relPath)
        assertEquals("Films 2.txt", db.shareFileDao().inFolder(rootId).single().relPath)
    }
}
