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
        const val DB12 = "migration-v12.db"
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

    /** A v12 database whose server is already pinned, the way the owner's was. */
    private fun seedV12() {
        helper.createDatabase(DB12, 12).use { db ->
            db.execSQL(
                "INSERT INTO servers (id, name, host, port, authMode, username, lastSeenAtMs, createdAtMs, unreachableSinceMs, addressMode) " +
                    "VALUES (1, 'Regolith', '192.168.4.82', 445, 'PASSWORD', 'jmeza', 100, 50, NULL, 'PINNED')",
            )
            db.execSQL(
                "INSERT INTO server_addresses (id, serverId, label, host, port, createdAtMs, lastOkAtMs, lastRttMs) " +
                    "VALUES (7, 1, 'Direct IP', '192.168.4.82', 445, 10, 900, 73)",
            )
            db.execSQL(
                "INSERT INTO server_addresses (id, serverId, label, host, port, createdAtMs, lastOkAtMs, lastRttMs) " +
                    "VALUES (8, 1, 'Tailscale', 'mini.ts.net', 445, 20, 900, 78)",
            )
        }
    }

    private fun migrated13(): RegolithDatabase {
        helper.runMigrationsAndValidate(DB12, 13, true)
        return Room.databaseBuilder(ApplicationProvider.getApplicationContext(), RegolithDatabase::class.java, DB12)
            .allowMainThreadQueries()
            .build()
            .also { helper.closeWhenFinished(it) }
    }

    @Test
    fun `a pin that was only implied becomes one that is written down`() = runTest {
        seedV12()
        val db = migrated13()

        assertEquals(
            "the address matching the server's host, not a guess made again later",
            7L,
            db.serverDao().byId(1)!!.pinnedAddressId,
        )
    }

    @Test
    fun `an address that has never been tried says so after the upgrade`() = runTest {
        seedV12()
        val db = migrated13()

        assertNull("v12 kept no record of attempts, so there is nothing to claim", db.serverAddressDao().byId(7)!!.lastTriedAtMs)
        assertEquals("what it last managed is still known", 73, db.serverAddressDao().byId(7)!!.lastRttMs)
    }

    @Test
    fun `a server nobody pinned comes through with no preference`() = runTest {
        seedV11()
        helper.runMigrationsAndValidate(DB, 13, true)
        val db = Room.databaseBuilder(ApplicationProvider.getApplicationContext(), RegolithDatabase::class.java, DB)
            .allowMainThreadQueries().build().also { helper.closeWhenFinished(it) }

        assertNull(db.serverDao().byId(1)!!.pinnedAddressId)
        assertEquals("AUTO", db.serverDao().byId(1)!!.addressMode)
    }
}
