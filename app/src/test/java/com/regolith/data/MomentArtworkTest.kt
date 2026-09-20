package com.regolith.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.regolith.data.artwork.ArtworkStore
import com.regolith.data.db.ArtworkEntity
import com.regolith.data.db.RegolithDatabase
import com.regolith.domain.artwork.ArtworkKind
import com.regolith.domain.artwork.ArtworkOwner
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * A point of interest's frame is addressed by ([ArtworkOwner.Moment.fileId],
 * [ArtworkOwner.Moment.startMs]), and everything about the feature rests on
 * that key being distinct per mark and stable across a rename.
 *
 * The bug these exist to prevent is a silent one: collide the key and two
 * marks in one film share a picture, which is the exact thing the feature was
 * built to stop, and it looks identical to the feature simply not working.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class MomentArtworkTest {

    private val store = ArtworkStore(ApplicationProvider.getApplicationContext())

    // ── The cache path ─────────────────────────────────────────────────

    @Test
    fun `two marks in one film are two different files`() {
        val first = store.relPathFor(ArtworkOwner.Moment(fileId = 12, startMs = 60_000), ArtworkKind.THUMB)
        val second = store.relPathFor(ArtworkOwner.Moment(fileId = 12, startMs = 754_000), ArtworkKind.THUMB)
        assertEquals("moment/12/60000/thumb.jpg", first)
        assertEquals("moment/12/754000/thumb.jpg", second)
        assertNotEquals(first, second)
    }

    @Test
    fun `a file's own path is untouched by the variant`() {
        // The variant defaults to "", so adding it must not have moved a single
        // existing image — every cached thumbnail would otherwise be a miss.
        assertEquals("file/7/thumb.jpg", store.relPathFor(ArtworkOwner.File(7), ArtworkKind.THUMB))
        assertEquals("file/7/poster.jpg", store.relPathFor(ArtworkOwner.File(7), ArtworkKind.POSTER))
        assertEquals("folder/3/thumb.jpg", store.relPathFor(ArtworkOwner.Folder(3), ArtworkKind.THUMB))
    }

    @Test
    fun `a mark's frame does not depend on its name`() {
        // The whole reason the key is the TIME and not the chapter row id:
        // renaming rewrites every row in the film, so a row-id key would lose
        // every frame in it. Two equal owners, so one cached picture.
        assertEquals(ArtworkOwner.Moment(12, 60_000), ArtworkOwner.Moment(12, 60_000))
        assertEquals(
            store.relPathFor(ArtworkOwner.Moment(12, 60_000), ArtworkKind.THUMB),
            store.relPathFor(ArtworkOwner.Moment(12, 60_000), ArtworkKind.THUMB),
        )
    }

    // ── The table ──────────────────────────────────────────────────────

    private fun db() = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext(),
        RegolithDatabase::class.java,
    ).allowMainThreadQueries().build()

    private fun momentRow(fileId: Long, startMs: Long) = ArtworkEntity(
        ownerType = "moment", ownerId = fileId, ownerVariant = startMs.toString(), kind = "THUMB",
        source = "FRAMEGRAB", relPath = "moment/$fileId/$startMs/thumb.jpg", width = 320, height = 180, updatedAtMs = 1,
    )

    @Test
    fun `moment rows coexist per mark and are dropped one at a time`() = runTest {
        val db = db()
        try {
            val dao = db.artworkDao()
            dao.upsert(momentRow(12, 60_000))
            dao.upsert(momentRow(12, 754_000))
            // Same film, same kind, different mark: the pre-v11 unique key was
            // (ownerType, ownerId, kind) and would have collapsed these to one.
            assertEquals(2, dao.momentsOf(12).size)

            dao.deleteMoment(12, "60000")
            assertEquals(listOf("754000"), dao.momentsOf(12).map { it.ownerVariant })
            assertNull(dao.get("moment", 12, "60000", "THUMB"))
        } finally {
            db.close()
        }
    }

    @Test
    fun `a moment frame and its film's own thumb are different rows`() = runTest {
        val db = db()
        try {
            val dao = db.artworkDao()
            dao.upsert(
                ArtworkEntity(
                    ownerType = "file", ownerId = 12, kind = "THUMB", source = "FRAMEGRAB",
                    relPath = "file/12/thumb.jpg", width = 320, height = 180, updatedAtMs = 1,
                ),
            )
            dao.upsert(momentRow(12, 60_000))
            // Both live at once: the film's thumb is what a moment falls back
            // to when its own seek cannot be trusted, so overwriting one with
            // the other would take the fallback away.
            assertEquals("file/12/thumb.jpg", dao.get("file", 12, "", "THUMB")!!.relPath)
            assertEquals("moment/12/60000/thumb.jpg", dao.get("moment", 12, "60000", "THUMB")!!.relPath)
            assertEquals(1, dao.momentsOf(12).size)
        } finally {
            db.close()
        }
    }
}
