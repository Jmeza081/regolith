package com.regolith.data

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.regolith.data.db.RegolithDatabase
import com.regolith.data.db.UploadEntity
import com.regolith.domain.transfer.UploadStatus
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The v13 → v14 upgrade (P16), run over a database that already holds a
 * library. It only adds the `uploads` table, which is exactly why it is
 * worth a test: an upgrade that "only adds a table" is the one nobody
 * checks, and the phone it lands on holds a scanned library and every
 * resume point. The table must also take a row that points at a folder,
 * and lose it when the folder goes.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class UploadsMigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), RegolithDatabase::class.java)

    private companion object {
        const val DB = "migration-v13.db"
    }

    private fun seedV13() {
        helper.createDatabase(DB, 13).use { db ->
            db.execSQL(
                "INSERT INTO servers (id, name, host, port, authMode, username, lastSeenAtMs, createdAtMs, unreachableSinceMs, addressMode) " +
                    "VALUES (1, 'TOWER', '192.168.4.82', 445, 'GUEST', NULL, 100, 50, NULL, 'AUTO')",
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
        helper.runMigrationsAndValidate(DB, 14, true)
        return Room.databaseBuilder(ApplicationProvider.getApplicationContext(), RegolithDatabase::class.java, DB)
            .allowMainThreadQueries()
            .build()
            .also { helper.closeWhenFinished(it) }
    }

    @Test
    fun `the library and its resume points come through untouched`() = runTest {
        seedV13()
        val db = migrated()
        assertEquals("Arrival.mkv", db.mediaFileDao().byId(30)!!.name)
        assertEquals(42_000L, db.playbackProgressDao().byFile(30)!!.positionMs)
    }

    @Test
    fun `the new table takes an upload into an existing folder, and lets it go with the folder`() = runTest {
        seedV13()
        val db = migrated()
        db.uploadDao().insertAll(
            listOf(
                UploadEntity(
                    batchId = 1, folderId = 20, sourceUri = "content://media/picker/0/1", targetName = "a.jpg",
                    sizeBytes = 10, sourceModifiedAtMs = null, conflictPolicy = "KEEP_BOTH", status = UploadStatus.QUEUED.name,
                    cause = null, causeBytes = null, bytesDone = 0, createdAtMs = 0, updatedAtMs = 0, finishedAtMs = null,
                ),
            ),
        )
        assertEquals(1, db.uploadDao().activeCount())

        db.openHelper.writableDatabase.execSQL("DELETE FROM folders WHERE id = 20")
        assertTrue("an upload into a folder that is gone goes with it", db.uploadDao().all().isEmpty())
    }
}
