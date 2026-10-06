package com.regolith.data

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.regolith.data.db.RegolithDatabase
import com.regolith.data.db.ShareFileEntity
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The v15 → v16 upgrade, run over a database that already holds a library.
 * It only adds the `share_files` table (the files in a folder that are not
 * videos), which is exactly why it is worth a test: see
 * [UploadsMigrationTest]. The table starts empty and fills as folders are
 * listed, and a row must go with its folder.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class ShareFilesMigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), RegolithDatabase::class.java)

    private companion object {
        const val DB = "migration-v15.db"
    }

    private fun seedV15() {
        helper.createDatabase(DB, 15).use { db ->
            db.execSQL(
                "INSERT INTO servers (id, name, host, port, authMode, username, lastSeenAtMs, createdAtMs, unreachableSinceMs, addressMode, pinnedAddressId) " +
                    "VALUES (1, 'TOWER', '192.168.4.82', 445, 'GUEST', NULL, 100, 50, NULL, 'AUTO', NULL)",
            )
            db.execSQL(
                "INSERT INTO shares (id, serverId, name, enabled, freeBytes, totalBytes, lastScanAtMs, writeChapters) " +
                    "VALUES (10, 1, 'media', 1, NULL, NULL, 300, 1)",
            )
            db.execSQL(
                "INSERT INTO folders (id, shareId, parentId, relPath, name, fileCount, byteCount, lastListedAtMs) " +
                    "VALUES (20, 10, NULL, '', 'media', 1, 10, 300)",
            )
            db.execSQL(
                "INSERT INTO media_files (id, shareId, folderId, relPath, name, ext, sizeBytes, modifiedAtMs, durationMs, missing, addedAtMs, lastSeenAtMs) " +
                    "VALUES (30, 10, 20, 'Arrival.mkv', 'Arrival.mkv', 'mkv', 10, 0, NULL, 0, 0, 0)",
            )
            db.execSQL("INSERT INTO playback_progress (fileId, positionMs, durationMs, completed, updatedAtMs) VALUES (30, 42000, 7000000, 0, 0)")
        }
    }

    private fun migrated(): RegolithDatabase {
        helper.runMigrationsAndValidate(DB, 16, true)
        return Room.databaseBuilder(ApplicationProvider.getApplicationContext(), RegolithDatabase::class.java, DB)
            .allowMainThreadQueries()
            .build()
            .also { helper.closeWhenFinished(it) }
    }

    @Test
    fun `the library and its resume points come through untouched`() = runTest {
        seedV15()
        val db = migrated()
        assertEquals("Arrival.mkv", db.mediaFileDao().byId(30)!!.name)
        assertEquals(42_000L, db.playbackProgressDao().byFile(30)!!.positionMs)
        assertTrue("the new table starts empty", db.shareFileDao().inFolder(20).isEmpty())
    }

    @Test
    fun `the new table takes a poster in an existing folder, and lets it go with the folder`() = runTest {
        seedV15()
        val db = migrated()
        db.shareFileDao().insert(
            ShareFileEntity(shareId = 10, folderId = 20, relPath = "poster.jpg", name = "poster.jpg", sizeBytes = 5, modifiedAtMs = 0),
        )
        assertEquals(listOf("poster.jpg"), db.shareFileDao().inFolder(20).map { it.name })

        db.openHelper.writableDatabase.execSQL("DELETE FROM folders WHERE id = 20")
        assertTrue("a file in a folder that is gone goes with it", db.shareFileDao().inFolder(20).isEmpty())
    }
}
