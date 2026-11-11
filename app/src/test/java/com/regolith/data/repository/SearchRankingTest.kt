package com.regolith.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.regolith.data.db.FolderEntity
import com.regolith.data.db.MediaFileEntity
import com.regolith.data.db.RegolithDatabase
import com.regolith.data.db.ServerEntity
import com.regolith.data.db.ShareEntity
import com.regolith.data.repository.LibraryRepository.Companion.ftsMatch
import com.regolith.data.repository.LibraryRepository.Companion.ftsMatchIn
import com.regolith.domain.library.SearchRank
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Search's ranking against Room's real full-text tables: the column-held
 * matches ([ftsMatchIn]) must find what they claim, since a wrong syntax
 * there matches nothing at all and fails silently.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class SearchRankingTest {
    private lateinit var db: RegolithDatabase
    private var shareId = 0L
    private var echoId = 0L
    private var miscId = 0L

    @Before
    fun setUp() = runTest {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), RegolithDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val serverId = db.serverDao().insert(
            ServerEntity(name = "TOWER", host = "tower", port = 445, authMode = "GUEST", username = null, lastSeenAtMs = null, createdAtMs = 0),
        )
        shareId = db.shareDao().insert(ShareEntity(serverId = serverId, name = "media", enabled = true, freeBytes = null, totalBytes = null, lastScanAtMs = null))
        val root = db.folderDao().upsert(FolderEntity(shareId = shareId, parentId = null, relPath = "", name = "media", fileCount = 0, byteCount = 0, lastListedAtMs = 1))
        val concerts = db.folderDao().upsert(FolderEntity(shareId = shareId, parentId = root.id, relPath = "Concerts", name = "Concerts", fileCount = 0, byteCount = 0, lastListedAtMs = 1))
        echoId = db.folderDao().upsert(
            FolderEntity(shareId = shareId, parentId = concerts.id, relPath = "Concerts/Live at The Echo", name = "Live at The Echo", fileCount = 2, byteCount = 0, lastListedAtMs = 1),
        ).id
        miscId = db.folderDao().upsert(FolderEntity(shareId = shareId, parentId = root.id, relPath = "Misc", name = "Misc", fileCount = 1, byteCount = 0, lastListedAtMs = 1)).id
        video(echoId, "Concerts/Live at The Echo", "Encore.mp4")
        video(echoId, "Concerts/Live at The Echo", "Opening Set.mp4")
        video(miscId, "Misc", "Echo Park.mp4", title = "Echo Park")
    }

    @After
    fun tearDown() = db.close()

    private suspend fun video(folderId: Long, folderPath: String, name: String, title: String? = null) =
        db.mediaFileDao().upsert(
            MediaFileEntity(
                shareId = shareId, folderId = folderId, relPath = "$folderPath/$name", name = name,
                ext = name.substringAfterLast('.'), sizeBytes = 1, modifiedAtMs = 1, durationMs = null,
                missing = false, addedAtMs = 1, lastSeenAtMs = 1, titleParsed = title,
            ),
        )

    private suspend fun files(match: String) = db.mediaFileDao().search(match, 50).first().map { it.name }.toSet()

    @Test fun `a column-held match finds words in that column only`() = runTest {
        assertEquals(setOf("Echo Park.mp4"), files(ftsMatchIn("name", "echo")!!))
        assertEquals(setOf("Echo Park.mp4"), files(ftsMatchIn("titleParsed", "ech par")!!))
        // The whole-index match also finds the two inside "Live at The Echo".
        assertEquals(setOf("Echo Park.mp4", "Encore.mp4", "Opening Set.mp4"), files(ftsMatch("echo")!!))
    }

    @Test fun `words that would be operators are plain words behind a column`() = runTest {
        // Neither throws nor turns into OR / NOT.
        assertEquals(emptySet<String>(), files(ftsMatchIn("name", "or not")!!))
        assertEquals(setOf("Encore.mp4"), files(ftsMatchIn("name", "\"encore\"*")!!))
    }

    @Test fun `the video named that way comes before those found through their folder`() = runTest {
        val dao = db.mediaFileDao()
        val ranked = SearchRank.ownFirst(
            dao.search(ftsMatchIn("name", "echo")!!, 2).first() + dao.search(ftsMatchIn("titleParsed", "echo")!!, 2).first(),
            dao.search(ftsMatch("echo")!!, 2).first(),
            limit = 2,
        ) { it.id }
        // Capped at two, the one NAMED Echo is never the one cut.
        assertEquals("Echo Park.mp4", ranked.first().name)
        assertEquals(2, ranked.size)
    }

    @Test fun `a folder named that way comes before one found through its parent`() = runTest {
        val dao = db.folderDao()
        val ranked = SearchRank.ownFirst(
            dao.searchFolders(ftsMatchIn("name", "concerts")!!, 10).first(),
            dao.searchFolders(ftsMatch("concerts")!!, 10).first(),
            limit = 10,
        ) { it.id }
        assertEquals(listOf("Concerts", "Live at The Echo"), ranked.map { it.name })
    }
}
