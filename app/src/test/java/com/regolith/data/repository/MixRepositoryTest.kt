package com.regolith.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.regolith.data.db.FolderEntity
import com.regolith.data.db.RegolithDatabase
import com.regolith.data.db.ServerEntity
import com.regolith.data.db.ShareEntity
import com.regolith.domain.library.MixOutcome
import com.regolith.domain.smb.ServerAccess
import com.regolith.domain.smb.SmbCredentials
import com.regolith.domain.smb.SmbHost
import com.regolith.testing.FakeSmbGateway
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Marking a folder a mix puts a hidden marker on the share, and the row follows. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class MixRepositoryTest {
    private lateinit var db: RegolithDatabase
    private lateinit var gateway: FakeSmbGateway
    private lateinit var repo: MixRepository
    private var assortedId = 0L

    @Before
    fun setUp() = runTest {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), RegolithDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        gateway = FakeSmbGateway()
        gateway.addShare("media")
        gateway.addFile("media", "Assorted/beach.mp4", ByteArray(10))
        val serverId = db.serverDao().insert(
            ServerEntity(name = "TOWER", host = "tower", port = 445, authMode = "GUEST", username = null, lastSeenAtMs = null, createdAtMs = 0),
        )
        val shareId = db.shareDao().insert(ShareEntity(serverId = serverId, name = "media", enabled = true, freeBytes = null, totalBytes = null, lastScanAtMs = null))
        val root = db.folderDao().upsert(FolderEntity(shareId = shareId, parentId = null, relPath = "", name = "media", fileCount = 0, byteCount = 0, lastListedAtMs = null))
        assortedId = db.folderDao().upsert(
            FolderEntity(shareId = shareId, parentId = root.id, relPath = "Assorted", name = "Assorted", fileCount = 1, byteCount = 10, lastListedAtMs = 1),
        ).id
        repo = MixRepository(
            db.folderDao(), db.shareDao(), db.serverDao(),
            object : ServerAccess {
                override suspend fun credentialsFor(serverId: Long): SmbCredentials = SmbCredentials.Guest
                override suspend fun hostFor(serverId: Long) = SmbHost("tower", 445)
            },
            gateway,
        )
    }

    @After
    fun tearDown() = db.close()

    @Test fun `marking a mix writes a hidden empty marker and flags the row`() = runTest {
        assertEquals(MixOutcome.DONE, repo.setMix(assortedId, true))

        assertEquals(0, gateway.files.getValue("media").getValue("Assorted/.regolith-mix").size)
        assertTrue("Assorted/.regolith-mix" in gateway.hidden.getValue("media"))
        assertTrue(repo.observeIsMix(assortedId).first())
    }

    @Test fun `marking it a collection again takes the marker away`() = runTest {
        repo.setMix(assortedId, true)

        assertEquals(MixOutcome.DONE, repo.setMix(assortedId, false))

        assertFalse("Assorted/.regolith-mix" in gateway.files.getValue("media"))
        assertFalse(repo.observeIsMix(assortedId).first())
    }

    @Test fun `the marker stays out of a plain listing, and shows to the scan`() = runTest {
        repo.setMix(assortedId, true)
        val host = SmbHost("tower", 445)

        val plain = gateway.list(host, SmbCredentials.Guest, "media", "Assorted").map { it.name }
        val scan = gateway.list(host, SmbCredentials.Guest, "media", "Assorted", keep = setOf(".regolith-mix")).map { it.name }

        assertEquals(listOf("beach.mp4"), plain)
        assertEquals(setOf("beach.mp4", ".regolith-mix"), scan.toSet())
    }

    @Test fun `a read-only share says so and changes nothing`() = runTest {
        gateway.readOnly = true

        assertEquals(MixOutcome.READ_ONLY, repo.setMix(assortedId, true))
        assertFalse(repo.observeIsMix(assortedId).first())
    }

    @Test fun `an unreachable server says so and changes nothing`() = runTest {
        gateway.reachable = false

        assertEquals(MixOutcome.UNREACHABLE, repo.setMix(assortedId, true))
        assertFalse(repo.observeIsMix(assortedId).first())
    }
}
