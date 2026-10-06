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
