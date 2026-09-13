package com.regolith.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.regolith.data.db.FolderEntity
import com.regolith.data.db.MediaFileEntity
import com.regolith.data.db.RegolithDatabase
import com.regolith.data.db.ServerEntity
import com.regolith.data.db.ShareEntity
import com.regolith.data.db.UserChapterEntity
import com.regolith.data.repository.LibraryRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Schema v8: a file's chapters are one value, keyed by the file, findable by name. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class UserChapterDaoTest {
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

    private suspend fun file(relPath: String, titleParsed: String? = null, missing: Boolean = false): Long = db.mediaFileDao().upsert(
        MediaFileEntity(
            shareId = shareId, folderId = folderId, relPath = relPath, name = relPath.substringAfterLast('/'), ext = "mkv",
            sizeBytes = 1, modifiedAtMs = 1, durationMs = null, missing = missing, addedAtMs = 1, lastSeenAtMs = 1, titleParsed = titleParsed,
        ),
    ).id

    private fun row(fileId: Long, startMs: Long, title: String?) = UserChapterEntity(fileId = fileId, startMs = startMs, title = title, updatedAtMs = 1)

    @Test
    fun `replace swaps the whole set and keeps it sorted`() = runTest {
        val heat = file("Films/Heat.1995.mkv", titleParsed = "Heat")
        db.userChapterDao().replaceForFile(heat, listOf(row(heat, 600_000, "The heist"), row(heat, 0, "Intro")))
        assertEquals(listOf(0L, 600_000L), db.userChapterDao().observeForFile(heat).first().map { it.startMs })

        db.userChapterDao().replaceForFile(heat, listOf(row(heat, 0, null)))
        val after = db.userChapterDao().observeForFile(heat).first()
        assertEquals(1, after.size)
        assertEquals(null, after.single().title)
    }

    @Test
    fun `rows go with the file`() = runTest {
        val heat = file("Films/Heat.1995.mkv")
        db.userChapterDao().replaceForFile(heat, listOf(row(heat, 0, "Intro")))
        db.openHelper.writableDatabase.execSQL("DELETE FROM media_files WHERE id = $heat")
        assertEquals(0, db.userChapterDao().observeForFile(heat).first().size)
        assertEquals(0, db.userChapterDao().observeTally().first().chapters)
    }

    @Test
    fun `search finds named chapters on present files only`() = runTest {
        val heat = file("Films/Heat.1995.mkv", titleParsed = "Heat")
        val gone = file("Films/Gone.mkv", missing = true)
        val ocean = file("Films/Oceans.Eleven.mkv", titleParsed = "Ocean's Eleven")
        db.userChapterDao().replaceForFile(heat, listOf(row(heat, 0, "Intro"), row(heat, 1_880_000, "The heist"), row(heat, 3_735_000, null)))
        db.userChapterDao().replaceForFile(gone, listOf(row(gone, 0, "Heist rehearsal")))
        db.userChapterDao().replaceForFile(ocean, listOf(row(ocean, 750_000, "The heist begins")))

        val hits = db.userChapterDao().search(LibraryRepository.ftsMatch("hei")!!, 20).first()
        assertEquals(listOf("The heist" to "Heat", "The heist begins" to "Ocean's Eleven"), hits.map { it.title to it.fileTitle })
        assertEquals(1_880_000L, hits.first().startMs)
        assertEquals("Films/Heat.1995.mkv", hits.first().fileRelPath)

        // An unnamed mark has no words; "part" must not find it.
        assertEquals(0, db.userChapterDao().search(LibraryRepository.ftsMatch("part")!!, 20).first().size)
    }

    @Test
    fun `tally counts rows and films, and clear zeroes both`() = runTest {
        val heat = file("Films/Heat.1995.mkv")
        val ocean = file("Films/Oceans.Eleven.mkv")
        db.userChapterDao().replaceForFile(heat, listOf(row(heat, 0, "Intro"), row(heat, 60_000, "Two")))
        db.userChapterDao().replaceForFile(ocean, listOf(row(ocean, 0, null)))
        val tally = db.userChapterDao().observeTally().first()
        assertEquals(3, tally.chapters)
        assertEquals(2, tally.files)

        db.userChapterDao().deleteAll()
        assertEquals(0, db.userChapterDao().observeTally().first().chapters)
        assertEquals(0, db.userChapterDao().observeTally().first().files)
    }
}
