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
import com.regolith.domain.media.ShortsRule
import kotlinx.coroutines.flow.first
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

    /**
     * The two writers pull in opposite directions on purpose: what frame
     * extraction learns in passing must never overwrite what the full probe
     * set, but the full probe is allowed to correct it.
     */
    @Test
    fun `fillBasics writes rotation once, saveProbe corrects it`() = runTest {
        val f = db.mediaFileDao().upsert(file("clip.mp4"))
        assertNull(db.mediaFileDao().byId(f.id)!!.rotationDegrees)

        // A portrait clip stored landscape: 1920x1080 with a quarter turn.
        db.mediaFileDao().fillBasics(f.id, 12_000, 1920, 1080, 90)
        db.mediaFileDao().byId(f.id)!!.let {
            assertEquals(90, it.rotationDegrees)
            assertEquals(1920, it.width)
            assertEquals(12_000L, it.durationMs)
        }

        // A second pass through the artwork path changes nothing.
        db.mediaFileDao().fillBasics(f.id, 99_000, 720, 1280, 270)
        db.mediaFileDao().byId(f.id)!!.let {
            assertEquals(90, it.rotationDegrees)
            assertEquals(1920, it.width)
            assertEquals(12_000L, it.durationMs)
        }

        // The container probe overwrites, and still protects the duration.
        db.mediaFileDao().saveProbe(
            id = f.id, durationMs = null, width = 1080, height = 1920, rotationDegrees = 0, frameRate = 30f,
            videoCodec = "video/avc", hdr = false, audioCodec = "audio/aac", audioChannels = 2, audioSampleRate = 48_000,
            probedAtMs = 1_000,
        )
        db.mediaFileDao().byId(f.id)!!.let {
            assertEquals(0, it.rotationDegrees)
            assertEquals(1080, it.width)
            assertEquals(12_000L, it.durationMs)
        }
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

    /**
     * Where the two halves of the Shorts rule meet: SQL throws away anything
     * long, unmeasured or gone from the share; Kotlin decides what counts as
     * portrait. This pins the seam -- that SQL hands the rule everything it
     * needs to judge, and nothing it would wrongly admit on its own.
     */
    @Test
    fun `short candidates drop the long, the gone and the unmeasured`() = runTest {
        suspend fun measured(name: String, ms: Long, w: Int, h: Int, rot: Int?) {
            val f = db.mediaFileDao().upsert(file(name))
            db.mediaFileDao().fillBasics(f.id, ms, w, h, rot)
        }
        measured("portrait.mkv", 15_000, 1080, 1920, null)   // plainly vertical
        measured("turned.mkv", 10_000, 1920, 1080, 90)       // vertical once turned
        measured("landscape.mkv", 30_000, 1920, 1080, null)  // short, but wide
        measured("long.mkv", 90_000, 1080, 1920, null)       // vertical, but a minute and a half
        measured("gone.mkv", 12_000, 1080, 1920, null)       // vertical and short, but off the share
        db.mediaFileDao().upsert(file("unmeasured.mkv"))     // nothing has opened it yet
        db.mediaFileDao().markMissingNotIn(folderId, listOf("portrait.mkv", "turned.mkv", "landscape.mkv", "long.mkv", "unmeasured.mkv"))

        val candidates = db.mediaFileDao().observeShortCandidates(listOf(shareId), ShortsRule.MAX_DURATION_MS).first()
        assertEquals(listOf("landscape.mkv", "portrait.mkv", "turned.mkv"), candidates.map { it.name }.sorted())

        val shorts = candidates.filter { ShortsRule.isShort(it.durationMs, it.width, it.height, it.rotationDegrees) }
        assertEquals(listOf("portrait.mkv", "turned.mkv"), shorts.map { it.name }.sorted())
    }

    @Test
    fun `folder upsert is stable by path`() = runTest {
        val f1 = db.folderDao().upsert(FolderEntity(shareId = shareId, parentId = folderId, relPath = "Films", name = "Films", fileCount = 0, byteCount = 0, lastListedAtMs = null))
        val f2 = db.folderDao().upsert(FolderEntity(shareId = shareId, parentId = folderId, relPath = "Films", name = "Films", fileCount = 3, byteCount = 9, lastListedAtMs = 5))
        assertEquals(f1.id, f2.id)
        assertEquals(3, db.folderDao().byId(f1.id)!!.fileCount)
    }
}
