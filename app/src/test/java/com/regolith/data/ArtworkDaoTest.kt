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
            assertEquals("SIDECAR", db.artworkDao().get("file", 7, "", "THUMB")!!.source)
            assertEquals(2, db.artworkDao().observeCount().first())
            db.artworkDao().deleteAll()
            assertEquals(0, db.artworkDao().observeCount().first())
        } finally {
            db.close()
        }
    }

    @Test
    fun `the wall's piles are the folders whose poster is a deck that knows its shape`() = runTest {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), RegolithDatabase::class.java).allowMainThreadQueries().build()
        fun folder(id: Long, kind: String = "POSTER", source: String = "DECK", shape: String? = "TALL") = ArtworkEntity(
            ownerType = "folder", ownerId = id, kind = kind, source = source, relPath = "folder/$id/$kind.jpg",
            width = 500, height = 750, updatedAtMs = 1, deckShape = shape,
        )
        try {
            db.artworkDao().upsert(folder(1))
            db.artworkDao().upsert(folder(2, shape = "WIDE"))
            // A deck dealt before piles were, a folder's own picture, and a deck's thumb: none is a pile.
            db.artworkDao().upsert(folder(3, shape = null))
            db.artworkDao().upsert(folder(4, source = "SIDECAR", shape = null))
            db.artworkDao().upsert(folder(5, kind = "THUMB", shape = null))
            val piles = db.artworkDao().observeFolderPiles().first().associate { it.ownerId to it.deckShape }
            assertEquals(mapOf(1L to "TALL", 2L to "WIDE"), piles)
            // Its own picture now: the row is written again, and the pile goes with it.
            db.artworkDao().upsert(folder(1, source = "SIDECAR", shape = null))
            assertEquals(listOf(2L), db.artworkDao().observeFolderPiles().first().map { it.ownerId })
        } finally {
            db.close()
        }
    }

    @Test
    fun `a folder's placeholder can go without its real pictures`() = runTest {
        // A folder placeholdered while it was still empty gets a second
        // chance once a scan finds videos in it — but a deck or a poster
        // already made, for this folder or any other, must survive that.
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), RegolithDatabase::class.java).allowMainThreadQueries().build()
        fun folder(id: Long, kind: String, source: String) = ArtworkEntity(
            ownerType = "folder", ownerId = id, kind = kind, source = source, relPath = if (source == "PLACEHOLDER") "" else "folder/$id/$kind.jpg",
            width = 0, height = 0, updatedAtMs = 1,
        )
        try {
            db.artworkDao().upsert(folder(3, "POSTER", "PLACEHOLDER"))
            db.artworkDao().upsert(folder(3, "THUMB", "PLACEHOLDER"))
            db.artworkDao().upsert(folder(4, "POSTER", "PLACEHOLDER"))
            db.artworkDao().upsert(folder(5, "POSTER", "DECK"))
            db.artworkDao().upsert(row("THUMB", source = "PLACEHOLDER")) // file 7, same id space elsewhere

            db.artworkDao().deleteFolderPlaceholder(3)
            db.artworkDao().deleteFolderPlaceholder(5)

            assertEquals(null, db.artworkDao().get("folder", 3, "", "POSTER"))
            assertEquals(null, db.artworkDao().get("folder", 3, "", "THUMB"))
            assertEquals("PLACEHOLDER", db.artworkDao().get("folder", 4, "", "POSTER")!!.source)
            assertEquals("DECK", db.artworkDao().get("folder", 5, "", "POSTER")!!.source)
            assertEquals("PLACEHOLDER", db.artworkDao().get("file", 7, "", "THUMB")!!.source)
        } finally {
            db.close()
        }
    }

    @Test
    fun `the old mosaics go on their own, and a deck counts as the app's own picture`() = runTest {
        // ArtworkStore.GENERATION 4: a folder's 2×2 mosaic is dealt again as
        // a deck, and nothing else is touched — above all not a video's own
        // frame, each of which would be a trip to the share to make again.
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), RegolithDatabase::class.java).allowMainThreadQueries().build()
        fun poster(type: String, id: Long, source: String) = ArtworkEntity(
            ownerType = type, ownerId = id, kind = "POSTER", source = source, relPath = "$type/$id/poster.jpg",
            width = 500, height = 750, updatedAtMs = 1,
        )
        try {
            val dao = db.artworkDao()
            dao.upsert(poster("folder", 1, "MOSAIC"))
            dao.upsert(poster("folder", 2, "DECK"))
            dao.upsert(poster("folder", 3, "SIDECAR"))
            dao.upsert(poster("file", 4, "FRAMEGRAB"))

            assertEquals(listOf(1L), dao.withSource("MOSAIC").map { it.ownerId })
            assertEquals(listOf(2L), dao.withSource("DECK").map { it.ownerId })
            assertEquals(setOf(1L, 2L, 4L), dao.generated().map { it.ownerId }.toSet())

            dao.deleteWithSource("MOSAIC")
            assertEquals(null, dao.get("folder", 1, "", "POSTER"))
            assertEquals("DECK", dao.get("folder", 2, "", "POSTER")!!.source)
            assertEquals("SIDECAR", dao.get("folder", 3, "", "POSTER")!!.source)
            assertEquals("FRAMEGRAB", dao.get("file", 4, "", "POSTER")!!.source)
        } finally {
            db.close()
        }
    }

    @Test
    fun `frames grabbed with no runtime known are found, and nothing else is`() = runTest {
        // ArtworkStore.GENERATION 6: a frame on a row with no runtime came from
        // the video's first moment. Not a cover image read off the file, not a
        // frame of a video whose runtime is known, and no folder's picture.
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), RegolithDatabase::class.java).allowMainThreadQueries().build()
        try {
            db.openHelper.writableDatabase.apply {
                execSQL("INSERT INTO servers (id, name, host, port, authMode, username, lastSeenAtMs, createdAtMs) VALUES (1, 'TOWER', 'tower', 445, 'GUEST', NULL, NULL, 0)")
                execSQL("INSERT INTO shares (id, serverId, name, enabled, freeBytes, totalBytes, lastScanAtMs) VALUES (1, 1, 'media', 1, NULL, NULL, NULL)")
                execSQL("INSERT INTO folders (id, shareId, parentId, relPath, name, fileCount, byteCount, lastListedAtMs) VALUES (3, 1, NULL, 'Clips', 'Clips', 3, 30, NULL)")
                for ((id, duration) in listOf(10 to "NULL", 11 to "0", 12 to "60000", 13 to "NULL")) {
                    execSQL(
                        "INSERT INTO media_files (id, shareId, folderId, relPath, name, ext, sizeBytes, modifiedAtMs, durationMs, missing, addedAtMs, lastSeenAtMs) " +
                            "VALUES ($id, 1, 3, 'Clips/$id.mkv', '$id.mkv', 'mkv', 10, 0, $duration, 0, 0, 0)",
                    )
                }
            }
            fun art(type: String, id: Long, source: String, kind: String = "POSTER") = ArtworkEntity(
                ownerType = type, ownerId = id, kind = kind, source = source, relPath = "$type/$id/$kind.jpg", width = 500, height = 750, updatedAtMs = 1,
            )
            val dao = db.artworkDao()
            dao.upsert(art("file", 10, "FRAMEGRAB"))
            dao.upsert(art("file", 10, "FRAMEGRAB", kind = "THUMB"))
            dao.upsert(art("file", 11, "FRAMEGRAB"))
            dao.upsert(art("file", 12, "FRAMEGRAB"))
            dao.upsert(art("file", 13, "EMBEDDED"))
            dao.upsert(art("folder", 3, "DECK"))

            assertEquals(setOf(10L to "POSTER", 10L to "THUMB", 11L to "POSTER"), dao.framesFromTheStart().map { it.ownerId to it.kind }.toSet())
            dao.deleteFramesFromTheStart()
            assertEquals(null, dao.get("file", 10, "", "THUMB"))
            assertEquals("FRAMEGRAB", dao.get("file", 12, "", "POSTER")!!.source)
            assertEquals("EMBEDDED", dao.get("file", 13, "", "POSTER")!!.source)
            assertEquals("DECK", dao.get("folder", 3, "", "POSTER")!!.source)
        } finally {
            db.close()
        }
    }

    @Test
    fun `marks borrowing their film's thumb are found apart from marks with frames of their own`() = runTest {
        // ArtworkStore.GENERATION 7: a mark's row pointing at its film's
        // picture (under file/) borrowed it; one under moment/ is its own.
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), RegolithDatabase::class.java).allowMainThreadQueries().build()
        try {
            val dao = db.artworkDao()
            fun mark(fileId: Long, at: Long, relPath: String) = ArtworkEntity(
                ownerType = "moment", ownerId = fileId, ownerVariant = at.toString(), kind = "THUMB", source = "FRAMEGRAB",
                relPath = relPath, width = 320, height = 180, updatedAtMs = 1,
            )
            fun film(fileId: Long, kind: String, source: String = "FRAMEGRAB") = ArtworkEntity(
                ownerType = "file", ownerId = fileId, kind = kind, source = source, relPath = "file/$fileId/$kind.jpg", width = 320, height = 180, updatedAtMs = 1,
            )
            dao.upsert(mark(10, 30_000, "file/10/thumb.jpg"))
            dao.upsert(mark(10, 90_000, "moment/10/90000/thumb.jpg"))
            dao.upsert(mark(11, 5_000, "moment/11/5000/thumb.jpg"))
            dao.upsert(film(10, "POSTER"))
            dao.upsert(film(10, "THUMB"))
            dao.upsert(film(11, "THUMB"))
            dao.upsert(film(12, "THUMB", source = "EMBEDDED"))

            assertEquals(listOf(10L to "30000"), dao.momentsBorrowingFilms().map { it.ownerId to it.ownerVariant })
            assertEquals(setOf(10L, 11L), dao.filmFrames("THUMB").map { it.ownerId }.toSet())
            assertEquals(setOf("POSTER", "THUMB"), dao.filmFramesOf(listOf(10)).map { it.kind }.toSet())

            dao.deleteMomentsBorrowingFilms()
            dao.deleteFilmFramesOf(listOf(10))
            assertEquals(null, dao.get("moment", 10, "30000", "THUMB"))
            assertEquals("moment/10/90000/thumb.jpg", dao.get("moment", 10, "90000", "THUMB")!!.relPath)
            assertEquals(null, dao.get("file", 10, "", "POSTER"))
            assertEquals("FRAMEGRAB", dao.get("file", 11, "", "THUMB")!!.source)
            assertEquals("EMBEDDED", dao.get("file", 12, "", "THUMB")!!.source)
        } finally {
            db.close()
        }
    }

    @Test
    fun `a picture's thumbnail is found by its folder, and is an orphan once its file is gone`() = runTest {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), RegolithDatabase::class.java).allowMainThreadQueries().build()
        try {
            db.openHelper.writableDatabase.apply {
                execSQL("INSERT INTO servers (id, name, host, port, authMode, username, lastSeenAtMs, createdAtMs) VALUES (1, 'TOWER', 'tower', 445, 'GUEST', NULL, NULL, 0)")
                execSQL("INSERT INTO shares (id, serverId, name, enabled, freeBytes, totalBytes, lastScanAtMs) VALUES (1, 1, 'media', 1, NULL, NULL, NULL)")
                execSQL("INSERT INTO folders (id, shareId, parentId, relPath, name, fileCount, byteCount, lastListedAtMs) VALUES (3, 1, NULL, 'Lake', 'Lake', 0, 0, NULL)")
                execSQL("INSERT INTO folders (id, shareId, parentId, relPath, name, fileCount, byteCount, lastListedAtMs) VALUES (4, 1, NULL, 'Other', 'Other', 0, 0, NULL)")
                execSQL("INSERT INTO share_files (id, shareId, folderId, relPath, name, sizeBytes, modifiedAtMs) VALUES (40, 1, 3, 'Lake/IMG_1.jpg', 'IMG_1.jpg', 5, 1)")
                execSQL("INSERT INTO share_files (id, shareId, folderId, relPath, name, sizeBytes, modifiedAtMs) VALUES (41, 1, 4, 'Other/IMG_2.jpg', 'IMG_2.jpg', 5, 1)")
            }
            fun thumb(id: Long) = ArtworkEntity(
                ownerType = "picture", ownerId = id, kind = "PICTURE", source = "PICTURE",
                relPath = "picture/$id/picture.jpg", width = 480, height = 640, updatedAtMs = 1, sourceStamp = "5\t1",
            )
            val dao = db.artworkDao()
            dao.upsert(thumb(40))
            dao.upsert(thumb(41))

            assertEquals(listOf(40L), dao.picturesIn(3).map { it.ownerId })
            assertEquals(emptyList<Long>(), dao.orphans().map { it.ownerId })

            db.shareFileDao().delete(40)
            assertEquals("a picture gone from the share leaves its thumbnail behind", listOf(40L), dao.orphans().map { it.ownerId })
        } finally {
            db.close()
        }
    }

    @Test
    fun `a folder listing finds the pictures of the folder and of the films directly in it`() = runTest {
        // What ArtworkRepository.onFolderListed checks against the listing:
        // nothing further down (a subfolder is checked by its own listing),
        // and no chapter frames, which no image on the share can make stale.
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), RegolithDatabase::class.java).allowMainThreadQueries().build()
        try {
            db.openHelper.writableDatabase.apply {
                execSQL("INSERT INTO servers (id, name, host, port, authMode, username, lastSeenAtMs, createdAtMs) VALUES (1, 'TOWER', 'tower', 445, 'GUEST', NULL, NULL, 0)")
                execSQL("INSERT INTO shares (id, serverId, name, enabled, freeBytes, totalBytes, lastScanAtMs) VALUES (1, 1, 'media', 1, NULL, NULL, NULL)")
                execSQL("INSERT INTO folders (id, shareId, parentId, relPath, name, fileCount, byteCount, lastListedAtMs) VALUES (3, 1, NULL, 'Films', 'Films', 2, 20, NULL)")
                execSQL("INSERT INTO folders (id, shareId, parentId, relPath, name, fileCount, byteCount, lastListedAtMs) VALUES (4, 1, 3, 'Films/Heat', 'Heat', 1, 10, NULL)")
                for ((id, folder, name) in listOf(Triple(10, 3, "Arrival.mkv"), Triple(11, 3, "Sicario.mkv"), Triple(12, 4, "Heat.mkv"))) {
                    execSQL(
                        "INSERT INTO media_files (id, shareId, folderId, relPath, name, ext, sizeBytes, modifiedAtMs, durationMs, missing, addedAtMs, lastSeenAtMs) " +
                            "VALUES ($id, 1, $folder, '$name', '$name', 'mkv', 10, 0, NULL, 0, 0, 0)",
                    )
                }
            }
            fun picture(type: String, id: Long, variant: String = "", stamp: String? = null) = ArtworkEntity(
                ownerType = type, ownerId = id, ownerVariant = variant, kind = "THUMB", source = "SIDECAR",
                relPath = "$type/$id/thumb.jpg", width = 320, height = 180, updatedAtMs = 1, sourceStamp = stamp,
            )
            val dao = db.artworkDao()
            dao.upsert(picture("folder", 3, stamp = "folder.jpg\t90210\t1000"))
            dao.upsert(picture("file", 10, stamp = ""))
            dao.upsert(picture("file", 11))
            dao.upsert(picture("moment", 10, variant = "60000"))
            dao.upsert(picture("folder", 4))
            dao.upsert(picture("file", 12))

            val found = dao.forFolderAndItsFiles(3)
            assertEquals(setOf("folder" to 3L, "file" to 10L, "file" to 11L), found.map { it.ownerType to it.ownerId }.toSet())
            assertEquals("the stamp is kept as written", "folder.jpg\t90210\t1000", found.single { it.ownerType == "folder" }.sourceStamp)
            assertEquals("", found.single { it.ownerId == 10L }.sourceStamp)
            assertEquals("unknown stays unknown, not empty", null, found.single { it.ownerId == 11L }.sourceStamp)
        } finally {
            db.close()
        }
    }

    @Test
    fun `version 1 database migrates to 11 with its files intact and indexed`() {
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
        // Every step of the chain, up to the current version: the whole point
        // of exporting schemas is that an old install can still be opened.
        val v10 = migrations.runMigrationsAndValidate(name, 10, true)
        v10.query("SELECT name, width, probedAtMs, titleParsed FROM media_files").use { c ->
            assertEquals(true, c.moveToFirst())
            assertEquals("a.mkv", c.getString(0))
            assertEquals(true, c.isNull(1))
            assertEquals(true, c.isNull(3))
        }
        v10.query("SELECT COUNT(*) FROM transfers").use { c ->
            c.moveToFirst()
            assertEquals(0, c.getInt(0))
        }
        v10.query("SELECT COUNT(*) FROM artwork").use { c ->
            c.moveToFirst()
            assertEquals(0, c.getInt(0))
        }
        // v5: a share with no rows here is still the whole share, which is
        // what every share was before folders could be chosen.
        v10.query("SELECT COUNT(*) FROM share_roots").use { c ->
            c.moveToFirst()
            assertEquals(0, c.getInt(0))
        }
        // v6: nothing is owed on an install that has never picked a folder.
        // v7: the exclusion columns exist and default to empty.
        v10.query("SELECT COUNT(*), COALESCE(MAX(excludedFileIds), ''), COALESCE(MAX(excludedPaths), '') FROM download_picks").use { c ->
            c.moveToFirst()
            assertEquals(0, c.getInt(0))
            assertEquals("", c.getString(1))
            assertEquals("", c.getString(2))
        }
        // v8: no chapters written yet, and the index over them exists and is empty.
        v10.query("SELECT COUNT(*) FROM user_chapters").use { c ->
            c.moveToFirst()
            assertEquals(0, c.getInt(0))
        }
        v10.query("SELECT COUNT(*) FROM user_chapter_fts WHERE user_chapter_fts MATCH '\"a\"*'").use { c ->
            c.moveToFirst()
            assertEquals(0, c.getInt(0))
        }
        // v9: nothing to sync yet, and every existing share writes chapters by default.
        v10.query("SELECT COUNT(*) FROM chapter_sync").use { c ->
            c.moveToFirst()
            assertEquals(0, c.getInt(0))
        }
        v10.query("SELECT writeChapters FROM shares WHERE id = 1").use { c ->
            c.moveToFirst()
            assertEquals(1, c.getInt(0))
        }
        // v10: the rotation column is there, and null on a row nothing has
        // measured yet -- which is what keeps it out of the Shorts feed.
        v10.query("SELECT rotationDegrees FROM media_files WHERE id = 1").use { c ->
            c.moveToFirst()
            assertEquals(true, c.isNull(0))
        }
        // The full-text index was rebuilt from the rows that already existed.
        v10.query("SELECT COUNT(*) FROM media_files JOIN media_fts ON media_files.id = media_fts.rowid WHERE media_fts MATCH '\"a\"*'").use { c ->
            c.moveToFirst()
            assertEquals(1, c.getInt(0))
        }

        // v11 gets its own step, because the risk it carries is not "does the
        // column appear" but "does an image cached BEFORE it survive". A row
        // written the old way has to come out the other side addressable, and
        // it is only addressable if ownerVariant defaulted to ''.
        v10.execSQL(
            "INSERT INTO artwork (ownerType, ownerId, kind, source, relPath, width, height, updatedAtMs) " +
                "VALUES ('file', 1, 'THUMB', 'FRAMEGRAB', 'file/1/thumb.jpg', 320, 180, 1)",
        )
        v10.close()

        val v11 = migrations.runMigrationsAndValidate(name, 11, true)
        v11.query("SELECT ownerVariant, relPath FROM artwork WHERE ownerType = 'file' AND ownerId = 1 AND kind = 'THUMB'").use { c ->
            assertEquals(true, c.moveToFirst())
            assertEquals("a thumb cached before v11 keeps its identity", "", c.getString(0))
            assertEquals("file/1/thumb.jpg", c.getString(1))
        }
        // And the widened unique key lets one film hold a frame per mark, which
        // the old three-column index would have rejected as a duplicate.
        v11.execSQL(
            "INSERT INTO artwork (ownerType, ownerId, ownerVariant, kind, source, relPath, width, height, updatedAtMs) " +
                "VALUES ('moment', 1, '60000', 'THUMB', 'FRAMEGRAB', 'moment/1/60000/thumb.jpg', 320, 180, 1)",
        )
        v11.execSQL(
            "INSERT INTO artwork (ownerType, ownerId, ownerVariant, kind, source, relPath, width, height, updatedAtMs) " +
                "VALUES ('moment', 1, '120000', 'THUMB', 'FRAMEGRAB', 'moment/1/120000/thumb.jpg', 320, 180, 1)",
        )
        v11.query("SELECT COUNT(*) FROM artwork WHERE ownerType = 'moment' AND ownerId = 1").use { c ->
            c.moveToFirst()
            assertEquals("two marks in one film are two rows", 2, c.getInt(0))
        }
        v11.close()
    }
}
