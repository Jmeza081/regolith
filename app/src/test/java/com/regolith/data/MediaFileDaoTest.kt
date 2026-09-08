package com.regolith.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.regolith.data.db.FolderEntity
import com.regolith.data.db.MediaFileEntity
import com.regolith.data.db.PlaybackProgressEntity
import com.regolith.data.db.RegolithDatabase
import com.regolith.data.db.ServerEntity
import com.regolith.data.db.ShareEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Guardrail G3: file identity is (shareId, relPath) and survives rescans. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class MediaFileDaoTest {
    private lateinit var db: RegolithDatabase
    private var shareId = 0L
    private var folderId = 0L

    @Before
    fun setUp() = runTest {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), RegolithDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val serverId = db.serverDao().insert(ServerEntity(name = "TOWER", host = "tower", port = 445, authMode = "GUEST", username = null, lastSeenAtMs = null, createdAtMs = 0))
        shareId = db.shareDao().insert(ShareEntity(serverId = serverId, name = "media", enabled = true, freeBytes = null, totalBytes = null, lastScanAtMs = null))
        folderId = db.folderDao().upsert(FolderEntity(shareId = shareId, parentId = null, relPath = "", name = "media", fileCount = 0, byteCount = 0, lastListedAtMs = null)).id
    }

    @After
    fun tearDown() = db.close()

    private fun file(relPath: String, size: Long = 100) = MediaFileEntity(
        shareId = shareId, folderId = folderId, relPath = relPath, name = relPath.substringAfterLast('/'), ext = "mkv",
        sizeBytes = size, modifiedAtMs = 1, durationMs = null, missing = false, addedAtMs = 1, lastSeenAtMs = 1,
    )

    @Test
    fun `upsert keeps the id and refreshes size`() = runTest {
        val first = db.mediaFileDao().upsert(file("a.mkv", size = 100))
        val second = db.mediaFileDao().upsert(file("a.mkv", size = 200))
        assertEquals(first.id, second.id)
        assertEquals(200, db.mediaFileDao().byId(first.id)!!.sizeBytes)
    }

    @Test
    fun `rescan marks missing instead of deleting, and progress survives`() = runTest {
        val a = db.mediaFileDao().upsert(file("a.mkv"))
        db.mediaFileDao().upsert(file("b.mkv"))
        db.playbackProgressDao().upsert(PlaybackProgressEntity(fileId = a.id, positionMs = 5_000, durationMs = 60_000, completed = false, updatedAtMs = 1))

        db.mediaFileDao().markMissingNotIn(folderId, listOf("b.mkv"))
        val missing = db.mediaFileDao().byId(a.id)
        assertNotNull(missing)
        assertTrue(missing!!.missing)
        assertEquals(1, db.mediaFileDao().inFolder(folderId).size)

        // It comes back on the next listing with the same id and its progress intact.
        val back = db.mediaFileDao().upsert(file("a.mkv"))
        assertEquals(a.id, back.id)
        assertFalse(back.missing)
        assertEquals(5_000, db.playbackProgressDao().byFile(a.id)!!.positionMs)
    }

    @Test
    fun `folder upsert is stable by path`() = runTest {
        val f1 = db.folderDao().upsert(FolderEntity(shareId = shareId, parentId = folderId, relPath = "Films", name = "Films", fileCount = 0, byteCount = 0, lastListedAtMs = null))
        val f2 = db.folderDao().upsert(FolderEntity(shareId = shareId, parentId = folderId, relPath = "Films", name = "Films", fileCount = 3, byteCount = 9, lastListedAtMs = 5))
        assertEquals(f1.id, f2.id)
        assertEquals(3, db.folderDao().byId(f1.id)!!.fileCount)
    }
}
