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
 * The v14 → v15 upgrade: artwork rows gain `sourceStamp`. A picture cached
 * before it must come through with the stamp UNKNOWN (null), not "no
 * images" (""): unknown is what makes a picture read off the share be read
 * once more, which is how a folder.jpg replaced before this version is
 * finally noticed. "" would declare it checked.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class ArtworkStampMigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), RegolithDatabase::class.java)

    private companion object {
        const val DB = "migration-v14.db"
    }

    @Test
    fun `a picture cached before stamps keeps its file and has an unknown stamp`() = runTest {
        helper.createDatabase(DB, 14).use { db ->
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
                    "VALUES (20, 10, NULL, '', 'media', 0, 0, 300)",
            )
            db.execSQL(
                "INSERT INTO artwork (ownerType, ownerId, ownerVariant, kind, source, relPath, width, height, updatedAtMs) " +
                    "VALUES ('folder', 20, '', 'POSTER', 'SIDECAR', 'folder/20/poster.jpg', 500, 750, 1)",
            )
        }
        helper.runMigrationsAndValidate(DB, 15, true)
        val db = Room.databaseBuilder(ApplicationProvider.getApplicationContext(), RegolithDatabase::class.java, DB)
            .allowMainThreadQueries()
            .build()
            .also { helper.closeWhenFinished(it) }

        val row = db.artworkDao().forFolderAndItsFiles(20).single()
        assertEquals("folder/20/poster.jpg", row.relPath)
        assertEquals("SIDECAR", row.source)
        assertNull(row.sourceStamp)
    }
}
