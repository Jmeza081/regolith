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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The v11 → v12 upgrade, run over a database that already holds a library.
 *
 * This is the migration that worried me most in the whole change: it lands
 * on a phone holding thousands of scanned files, their artwork and every
 * resume point, and the one thing it must not do is leave a server with no
 * way to reach it — an empty address list reads as "unreachable", so a
 * library that worked yesterday would come back broken.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class ServerAddressMigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), RegolithDatabase::class.java)

    private companion object {
        const val DB = "migration-v11.db"
    }

    /** A v11 database with two servers and a file under one of them. */
    private fun seedV11() {
        helper.createDatabase(DB, 11).use { db ->
            db.execSQL(
                "INSERT INTO servers (id, name, host, port, authMode, username, lastSeenAtMs, createdAtMs, unreachableSinceMs) " +
                    "VALUES (1, 'TOWER', '192.168.4.82', 445, 'PASSWORD', 'jmeza', 100, 50, NULL)",
            )
            db.execSQL(
                "INSERT INTO servers (id, name, host, port, authMode, username, lastSeenAtMs, createdAtMs, unreachableSinceMs) " +
                    "VALUES (2, 'STUDIO', 'studio.local', 1445, 'GUEST', NULL, 200, 60, 900)",
            )
            db.execSQL(
                "INSERT INTO shares (id, serverId, name, enabled, freeBytes, totalBytes, lastScanAtMs, writeChapters) " +
                    "VALUES (10, 1, 'Regolith', 1, NULL, NULL, 300, 1)",
            )
            db.execSQL(
                "INSERT INTO folders (id, shareId, parentId, relPath, name, fileCount, byteCount, lastListedAtMs) " +
                    "VALUES (20, 10, NULL, '', 'Regolith', 1, 10, 300)",
            )
            db.execSQL(
                "INSERT INTO media_files (id, shareId, folderId, relPath, name, ext, sizeBytes, modifiedAtMs, durationMs, missing, addedAtMs, lastSeenAtMs) " +
                    "VALUES (30, 10, 20, 'Arrival.mkv', 'Arrival.mkv', 'mkv', 10, 0, NULL, 0, 0, 0)",
            )
            db.execSQL("INSERT INTO playback_progress (fileId, positionMs, durationMs, completed, updatedAtMs) VALUES (30, 42000, 7000000, 0, 0)")
        }
    }

    private fun migrated(): RegolithDatabase {
        helper.runMigrationsAndValidate(DB, 12, true)
        return Room.databaseBuilder(ApplicationProvider.getApplicationContext(), RegolithDatabase::class.java, DB)
            .allowMainThreadQueries()
            .build()
            .also { helper.closeWhenFinished(it) }
    }

    @Test
    fun `every server comes out of the upgrade with the address it was using`() = runTest {
        seedV11()
        val db = migrated()

        val tower = db.serverAddressDao().forServer(1)
        assertEquals("exactly one address, not none and not two", 1, tower.size)
        assertEquals("192.168.4.82", tower.single().host)
        assertEquals(445, tower.single().port)

        val studio = db.serverAddressDao().forServer(2)
        assertEquals("studio.local", studio.single().host)
        assertEquals("a non-standard port survives", 1445, studio.single().port)
    }

    @Test
    fun `the seeded address is unnamed, so the UI shows the address itself`() = runTest {
        seedV11()
        val db = migrated()

        assertEquals("", db.serverAddressDao().forServer(1).single().label)
        assertNull("never tried yet", db.serverAddressDao().forServer(1).single().lastRttMs)
    }

    @Test
    fun `servers start in AUTO, so one address behaves exactly as before`() = runTest {
        seedV11()
        val db = migrated()

        assertEquals("AUTO", db.serverDao().byId(1)!!.addressMode)
        assertEquals("the host column is untouched by the upgrade", "192.168.4.82", db.serverDao().byId(1)!!.host)
    }

    @Test
    fun `the library, and where you got to in it, survive`() = runTest {
        seedV11()
        val db = migrated()

        assertEquals("Arrival.mkv", db.mediaFileDao().byId(30)!!.name)
        assertEquals(42_000L, db.playbackProgressDao().byFile(30)!!.positionMs)
        assertEquals("out of reach is remembered too", 900L, db.serverDao().byId(2)!!.unreachableSinceMs)
    }

    @Test
    fun `an address goes when its server does`() = runTest {
        seedV11()
        val db = migrated()

        db.serverDao().delete(1)

        assertTrue("the cascade reaches the new table", db.serverAddressDao().forServer(1).isEmpty())
        assertEquals("and leaves the other server alone", 1, db.serverAddressDao().forServer(2).size)
    }
}
