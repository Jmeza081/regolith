package com.regolith.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.regolith.data.artwork.ArtworkStore
import com.regolith.data.db.ArtworkEntity
import com.regolith.data.db.FolderEntity
import com.regolith.data.db.MediaFileEntity
import com.regolith.data.db.PlaybackProgressEntity
import com.regolith.data.db.RegolithDatabase
import com.regolith.data.db.ServerEntity
import com.regolith.data.db.ShareEntity
import com.regolith.data.db.TransferEntity
import com.regolith.data.db.UserChapterEntity
import com.regolith.data.transfer.DownloadStore
import com.regolith.domain.media.DeviceSource
import com.regolith.domain.transfer.TransferStatus
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
 * The promise a disconnect now makes: the media list goes, the copies stay.
 *
 * The mechanism under test is an ordering — adopt, THEN cascade — so every
 * case here deletes the server for real and asks what is left, rather than
 * asserting on the adoption in isolation.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class DeviceLibraryTest {
    private lateinit var db: RegolithDatabase
    private lateinit var downloads: DownloadStore
    private lateinit var artwork: ArtworkStore
    private lateinit var device: DeviceLibrary
    private lateinit var sweeper: StorageSweeper
    private var serverId = 0L
    private var shareId = 0L
    private var folderId = 0L

    @Before
    fun setUp() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, RegolithDatabase::class.java).allowMainThreadQueries().build()
        downloads = DownloadStore(context)
        artwork = ArtworkStore(context)
        downloads.root.deleteRecursively()
        artwork.root.deleteRecursively()
        device = DeviceLibrary(db.serverDao(), db.shareDao(), db.folderDao(), db.mediaFileDao(), db.transferDao(), downloads)
        sweeper = StorageSweeper(
            db.transferDao(), db.artworkDao(), db.mediaFileDao(), db.folderDao(), downloads, artwork, device,
        )
        serverId = db.serverDao().insert(
            ServerEntity(name = "TOWER", host = "tower", port = 445, authMode = "GUEST", username = null, lastSeenAtMs = null, createdAtMs = 0),
        )
        shareId = db.shareDao().insert(
            ShareEntity(serverId = serverId, name = "media", enabled = true, freeBytes = null, totalBytes = null, lastScanAtMs = null),
        )
        folderId = db.folderDao().upsert(
            FolderEntity(shareId = shareId, parentId = null, relPath = "", name = "media", fileCount = 0, byteCount = 0, lastListedAtMs = 1),
        ).id
    }

    @After
    fun tearDown() {
        db.close()
        downloads.root.deleteRecursively()
        artwork.root.deleteRecursively()
    }

    /** A file on the share, optionally with a finished copy of [bytes] on disk. */
    private suspend fun film(name: String, status: TransferStatus?, bytes: ByteArray = ByteArray(16)): Long {
        val id = db.mediaFileDao().insert(
            MediaFileEntity(
                shareId = shareId, folderId = folderId, relPath = "Films/$name", name = name, ext = "mkv",
                sizeBytes = bytes.size.toLong(), modifiedAtMs = 0, durationMs = null, missing = false,
                addedAtMs = 0, lastSeenAtMs = 0, titleParsed = name.substringBeforeLast('.'), year = 2016,
            ),
        )
        if (status != null) {
            val localPath = downloads.relPathFor(id, "mkv")
            db.transferDao().insert(
                TransferEntity(
                    fileId = id, status = status.name, bytesDone = bytes.size.toLong(), totalBytes = bytes.size.toLong(),
                    localPath = localPath, cause = null, causeBytes = null, createdAtMs = 0, updatedAtMs = 0, finishedAtMs = null,
                ),
            )
            downloads.root.mkdirs()
            if (status == TransferStatus.DONE) downloads.fileFor(localPath).writeBytes(bytes) else downloads.partFor(localPath).writeBytes(bytes)
        }
        return id
    }

    @Test
    fun `a finished copy survives its server, keeping its id`() = runTest {
        val kept = film("Arrival.mkv", TransferStatus.DONE)

        assertEquals(1, device.adopt(serverId).kept)
        db.serverDao().delete(serverId)

        val row = db.mediaFileDao().byId(kept)
        assertNotNull("the copy outlives the server it came from", row)
        assertEquals("the same row, so everything keyed on its id still points at it", kept, row!!.id)
        assertTrue("the bytes are still there", downloads.fileFor(downloads.relPathFor(kept, "mkv")).exists())
        assertNotNull("and the transfer row that knows where they are", db.transferDao().byFile(kept))
    }

    @Test
    fun `an adopted copy keeps its resume point and its chapters`() = runTest {
        val kept = film("Arrival.mkv", TransferStatus.DONE)
        db.playbackProgressDao().upsert(
            PlaybackProgressEntity(fileId = kept, positionMs = 42_000, durationMs = 7_000_000, completed = false, updatedAtMs = 0),
        )
        db.userChapterDao().replaceForFile(kept, listOf(UserChapterEntity(fileId = kept, startMs = 90_000, title = "the arrival", updatedAtMs = 0)))

        device.adopt(serverId)
        db.serverDao().delete(serverId)

        assertEquals(42_000L, db.playbackProgressDao().byFile(kept)?.positionMs)
        assertEquals(listOf("the arrival"), db.userChapterDao().forFile(kept).map { it.title })
    }

    @Test
    fun `an adopted copy moves to the device source and off the network`() = runTest {
        val kept = film("Arrival.mkv", TransferStatus.DONE)

        device.adopt(serverId)
        db.serverDao().delete(serverId)

        val deviceShareId = device.shareId()
        assertNotNull("a device source was made to hold it", deviceShareId)
        assertEquals(deviceShareId, db.mediaFileDao().byId(kept)!!.shareId)
        val deviceServer = db.serverDao().byHost(DeviceSource.HOST, 445)!!
        assertEquals(DeviceSource.NAME, deviceServer.name)
        val root = db.folderDao().byPath(deviceShareId!!, "")!!
        assertNotNull("its folder is listed, so Browse never says 'Not listed yet' about it", root.lastListedAtMs)
    }

    @Test
    fun `a half-copied file is discarded rather than adopted`() = runTest {
        val partial = film("Dune.mkv", TransferStatus.RUNNING)
        val partPath = downloads.partFor(downloads.relPathFor(partial, "mkv"))
        assertTrue(partPath.exists())

        val result = device.adopt(serverId)
        db.serverDao().delete(serverId)

        assertEquals("nothing to keep: it can never be resumed now", 0, result.kept)
        assertEquals(1, result.discarded)
        assertNull("the row went with the share", db.mediaFileDao().byId(partial))
        assertFalse("and so did the bytes", partPath.exists())
    }

    @Test
    fun `a file with no copy is not adopted`() = runTest {
        val streamed = film("Tenet.mkv", status = null)

        assertEquals(0, device.adopt(serverId).kept)
        db.serverDao().delete(serverId)

        assertNull("nothing on the phone, nothing to keep", db.mediaFileDao().byId(streamed))
    }

    @Test
    fun `the sweep collects downloads no row claims`() = runTest {
        downloads.root.mkdirs()
        val orphan = downloads.fileFor("999.mkv").apply { writeBytes(ByteArray(2048)) }
        val claimed = film("Arrival.mkv", TransferStatus.DONE)

        val swept = sweeper.sweep()

        assertEquals(1, swept.downloadFiles)
        assertEquals(2048, swept.bytes)
        assertFalse("the orphan is gone", orphan.exists())
        assertTrue("the claimed copy is not", downloads.fileFor(downloads.relPathFor(claimed, "mkv")).exists())
    }

    @Test
    fun `the sweep collects artwork whose owner has gone`() = runTest {
        val gone = film("Arrival.mkv", status = null)
        db.artworkDao().insert(
            ArtworkEntity(
                ownerType = "file", ownerId = gone, kind = "POSTER", source = "FRAMEGRAB",
                relPath = "file/$gone/poster.jpg", width = 2, height = 3, updatedAtMs = 0,
            ),
        )
        val image = artwork.fileFor("file/$gone/poster.jpg").apply { parentFile?.mkdirs(); writeBytes(ByteArray(512)) }
        db.mediaFileDao().deleteByIds(listOf(gone))

        val swept = sweeper.sweep()

        assertEquals(1, swept.artworkRows)
        assertFalse("the image goes with the row", image.exists())
        assertTrue("and so does the directory that held it", artwork.fileFor("file/$gone").listFiles().isNullOrEmpty())
    }

    @Test
    fun `the sweep takes an empty device source away`() = runTest {
        val kept = film("Arrival.mkv", TransferStatus.DONE)
        device.adopt(serverId)
        db.serverDao().delete(serverId)
        assertNotNull(db.serverDao().byHost(DeviceSource.HOST, 445))

        // Everything the user kept, removed from the On-this-device page.
        db.mediaFileDao().deleteByIds(listOf(kept))
        sweeper.sweep()

        assertNull("an empty 'This device' is worse than none", db.serverDao().byHost(DeviceSource.HOST, 445))
    }
}
