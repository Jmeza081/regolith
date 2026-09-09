package com.regolith.data

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.regolith.data.db.ArtworkEntity
import com.regolith.data.db.RegolithDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Guardrail G5: artwork rows are keyed by what they belong to; and the v1 -> v2 upgrade keeps data. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class ArtworkDaoTest {

    @get:Rule
    val migrations = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), RegolithDatabase::class.java)

    private fun row(kind: String, source: String = "FRAMEGRAB") = ArtworkEntity(
        ownerType = "file", ownerId = 7, kind = kind, source = source, relPath = "file/7/$kind.jpg", width = 320, height = 180, updatedAtMs = 1,
    )

    @Test
    fun `upsert replaces by owner and kind`() = runTest {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), RegolithDatabase::class.java).allowMainThreadQueries().build()
        try {
            val first = db.artworkDao().upsert(row("THUMB", source = "PLACEHOLDER"))
            val second = db.artworkDao().upsert(row("THUMB", source = "SIDECAR"))
            db.artworkDao().upsert(row("POSTER"))
            assertEquals(first.id, second.id)
            assertEquals("SIDECAR", db.artworkDao().get("file", 7, "THUMB")!!.source)
            assertEquals(2, db.artworkDao().observeCount().first())
            db.artworkDao().deleteAll()
            assertEquals(0, db.artworkDao().observeCount().first())
        } finally {
            db.close()
        }
    }

    @Test
    fun `version 1 database migrates to 3 with its files intact and indexed`() {
        val name = "migrate-test.db"
        migrations.createDatabase(name, 1).use { v1 ->
            v1.execSQL("INSERT INTO servers (id, name, host, port, authMode, username, lastSeenAtMs, createdAtMs) VALUES (1, 'TOWER', 'tower', 445, 'GUEST', NULL, NULL, 0)")
            v1.execSQL("INSERT INTO shares (id, serverId, name, enabled, freeBytes, totalBytes, lastScanAtMs) VALUES (1, 1, 'media', 1, NULL, NULL, NULL)")
            v1.execSQL("INSERT INTO folders (id, shareId, parentId, relPath, name, fileCount, byteCount, lastListedAtMs) VALUES (1, 1, NULL, '', 'media', 1, 10, NULL)")
            v1.execSQL(
                "INSERT INTO media_files (id, shareId, folderId, relPath, name, ext, sizeBytes, modifiedAtMs, durationMs, missing, addedAtMs, lastSeenAtMs) " +
                    "VALUES (1, 1, 1, 'a.mkv', 'a.mkv', 'mkv', 10, 0, NULL, 0, 0, 0)",
            )
        }
        val v3 = migrations.runMigrationsAndValidate(name, 3, true)
        v3.query("SELECT name, width, probedAtMs, titleParsed FROM media_files").use { c ->
            assertEquals(true, c.moveToFirst())
            assertEquals("a.mkv", c.getString(0))
            assertEquals(true, c.isNull(1))
            assertEquals(true, c.isNull(3))
        }
        v3.query("SELECT COUNT(*) FROM artwork").use { c ->
            c.moveToFirst()
            assertEquals(0, c.getInt(0))
        }
        // The full-text index was rebuilt from the rows that already existed.
        v3.query("SELECT COUNT(*) FROM media_files JOIN media_fts ON media_files.id = media_fts.rowid WHERE media_fts MATCH '\"a\"*'").use { c ->
            c.moveToFirst()
            assertEquals(1, c.getInt(0))
        }
        v3.close()
    }
}
