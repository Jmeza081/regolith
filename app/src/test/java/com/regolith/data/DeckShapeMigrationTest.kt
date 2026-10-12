package com.regolith.data

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.regolith.data.db.RegolithDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The v17 → v18 upgrade: `artwork` gains a deck's pile shape. Every row made
 * before comes through as it was, its deck reading as "no pile", which the
 * Library's wall boxes as before until the deck is dealt again.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class DeckShapeMigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), RegolithDatabase::class.java)

    private companion object {
        const val DB = "migration-v17.db"
    }

    @Test
    fun `a deck dealt before piles keeps its row and has no pile`() = runTest {
        helper.createDatabase(DB, 17).use { db ->
            db.execSQL(
                "INSERT INTO artwork (id, ownerType, ownerId, ownerVariant, kind, source, relPath, width, height, updatedAtMs, sourceStamp) " +
                    "VALUES (5, 'folder', 20, '', 'POSTER', 'DECK', 'folder/20/poster.jpg', 500, 750, 100, '')",
            )
        }
        helper.runMigrationsAndValidate(DB, 18, true)
        val db = Room.databaseBuilder(ApplicationProvider.getApplicationContext(), RegolithDatabase::class.java, DB)
            .allowMainThreadQueries()
            .build()
            .also { helper.closeWhenFinished(it) }

        val row = db.artworkDao().get("folder", 20, "", "POSTER")!!
        assertEquals("DECK", row.source)
        assertEquals("folder/20/poster.jpg", row.relPath)
        assertEquals("", row.sourceStamp)
        assertNull(row.deckShape)
        assertTrue(db.artworkDao().observeFolderPiles().first().isEmpty())
    }
}
