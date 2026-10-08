package com.regolith.data

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.regolith.data.db.RegolithDatabase
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The v16 → v17 upgrade: `share_files` gains a picture's shape, date taken,
 * camera and when they were read. Every row already listed must come through
 * with its id, reading as "not measured yet", so the next walk measures it.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class PictureFactsMigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), RegolithDatabase::class.java)

    private companion object {
        const val DB = "migration-v16.db"
    }

    @Test
    fun `listed pictures keep their rows and wait to be measured`() = runTest {
        helper.createDatabase(DB, 16).use { db ->
            db.execSQL(
                "INSERT INTO servers (id, name, host, port, authMode, username, lastSeenAtMs, createdAtMs, unreachableSinceMs, addressMode, pinnedAddressId) " +
                    "VALUES (1, 'TOWER', '192.168.4.82', 445, 'GUEST', NULL, 100, 50, NULL, 'AUTO', NULL)",
            )
            db.execSQL("INSERT INTO shares (id, serverId, name, enabled, freeBytes, totalBytes, lastScanAtMs, writeChapters) VALUES (10, 1, 'media', 1, NULL, NULL, 300, 1)")
            db.execSQL("INSERT INTO folders (id, shareId, parentId, relPath, name, fileCount, byteCount, lastListedAtMs) VALUES (20, 10, NULL, '', 'media', 0, 0, 300)")
            db.execSQL("INSERT INTO share_files (id, shareId, folderId, relPath, name, sizeBytes, modifiedAtMs) VALUES (40, 10, 20, 'IMG_1.heic', 'IMG_1.heic', 4200000, 7)")
        }
        helper.runMigrationsAndValidate(DB, 17, true)
        val db = Room.databaseBuilder(ApplicationProvider.getApplicationContext(), RegolithDatabase::class.java, DB)
            .allowMainThreadQueries()
            .build()
            .also { helper.closeWhenFinished(it) }

        val row = db.shareFileDao().byId(40)!!
        assertEquals("IMG_1.heic", row.name)
        assertEquals(4_200_000L, row.sizeBytes)
        assertNull(row.width)
        assertNull(row.measuredAtMs)
        assertEquals(listOf(40L), db.shareFileDao().unmeasuredInShare(10).map { it.id })
    }
}
