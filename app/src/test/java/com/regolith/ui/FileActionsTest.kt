package com.regolith.ui

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.regolith.data.db.FolderEntity
import com.regolith.data.db.MediaFileEntity
import com.regolith.data.db.RegolithDatabase
import com.regolith.data.db.ServerEntity
import com.regolith.data.db.ShareEntity
import com.regolith.data.fileops.FileOpsRepository
import com.regolith.data.repository.FolderLookup
import com.regolith.data.transfer.DownloadStore
import com.regolith.data.transfer.SelectionStore
import com.regolith.domain.media.Companions
import com.regolith.domain.media.DemoSource
import com.regolith.domain.smb.ServerAccess
import com.regolith.domain.smb.SmbCredentials
import com.regolith.domain.smb.SmbHost
import com.regolith.domain.transfer.FilePick
import com.regolith.domain.transfer.FolderPick
import com.regolith.testing.FakeSmbGateway
import com.regolith.ui.util.FileActions
import com.regolith.ui.util.FileActionsState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The verbs Browse and the Library share, driven the way the pill drives
 * them, against the real [FileOpsRepository] and a fake share. What the
 * controller adds on top of the repository is tested here: which verbs are
 * offered, where the move sheet opens, what "already here" means for a pick
 * gathered from several folders, and where Undo puts things back.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class FileActionsTest {
    private lateinit var db: RegolithDatabase
    private lateinit var gateway: FakeSmbGateway
    private lateinit var fileOps: FileOpsRepository
    private val selection = SelectionStore()
    private var shareId = 0L
    private var rootId = 0L
    private var filmsId = 0L
    private var archiveId = 0L

    @Before
    fun setUp() = runTest {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), RegolithDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        gateway = FakeSmbGateway()
        gateway.addShare("media")
        shareId = share("TOWER", "tower", "media")
        rootId = db.folderDao().upsert(FolderEntity(shareId = shareId, parentId = null, relPath = "", name = "media", fileCount = 0, byteCount = 0, lastListedAtMs = 1)).id
        filmsId = folder("Films", rootId)
        archiveId = folder("Archive", rootId)
        fileOps = FileOpsRepository(
            gateway, db.serverDao(), db.shareDao(), db.folderDao(), db.mediaFileDao(),
            object : ServerAccess {
                override suspend fun credentialsFor(serverId: Long): SmbCredentials = SmbCredentials.Guest
                override suspend fun hostFor(serverId: Long) = SmbHost("tower", 445)
            },
            db.transferDao(),
            DownloadStore(ApplicationProvider.getApplicationContext()),
            db.subtreeDao(),
            db.shareFileDao(),
        )
    }

    @After
    fun tearDown() = db.close()

    private suspend fun share(server: String, host: String, name: String): Long {
        val serverId = db.serverDao().insert(
            ServerEntity(name = server, host = host, port = 445, authMode = "GUEST", username = null, lastSeenAtMs = null, createdAtMs = 0),
        )
        return db.shareDao().insert(ShareEntity(serverId = serverId, name = name, enabled = true, freeBytes = null, totalBytes = null, lastScanAtMs = null))
    }

    private suspend fun folder(name: String, parentId: Long, parentPath: String = "", share: Long = shareId): Long {
        val relPath = if (parentPath.isEmpty()) name else "$parentPath/$name"
        if (share == shareId) gateway.addDir("media", relPath)
        return db.folderDao().upsert(
            FolderEntity(shareId = share, parentId = parentId, relPath = relPath, name = name, fileCount = 0, byteCount = 0, lastListedAtMs = 1),
        ).id
    }

    private suspend fun film(name: String, folderId: Long = filmsId, folderPath: String = "Films", share: Long = shareId): MediaFileEntity {
        val relPath = "$folderPath/$name"
        if (share == shareId) gateway.addFile("media", relPath, ByteArray(100))
        return db.mediaFileDao().upsert(
            MediaFileEntity(
                shareId = share, folderId = folderId, relPath = relPath, name = name, ext = name.substringAfterLast('.'),
                sizeBytes = 100, modifiedAtMs = 1, durationMs = null, missing = false, addedAtMs = 1, lastSeenAtMs = 1,
            ),
        )
    }

    private fun onShare(relPath: String) = gateway.files["media"]?.containsKey(relPath) == true

    private fun pick(file: MediaFileEntity) = selection.toggleFile(FilePick(file.id, file.shareId, file.relPath.substringBeforeLast('/'), file.sizeBytes))

    private fun pickFolder(id: Long, relPath: String) = selection.toggleFolder(FolderPick(id, shareId, relPath, listed = true))

    /** The controller, living as long as the test does, as a ViewModel's would. */
    private fun TestScope.actions() = FileActions(backgroundScope, DaoLookup(db), fileOps, selection)

    private suspend fun FileActions.awaitState(predicate: (FileActionsState) -> Boolean) = state.first(predicate)

    // ── which verbs are offered ────────────────────────────────────────

    @Test
    fun `one pick can be moved, renamed and deleted, but two cannot be renamed`() = runTest {
        val actions = actions()
        pick(film("Heat.mkv"))
        val one = actions.awaitState { it.verbs.canDelete }.verbs
        assertTrue(one.canMove && one.canRename)
        assertNull(one.hint)

        pick(film("Sicario.mkv"))
        val two = actions.awaitState { it.verbs.hint != null }.verbs
        assertTrue(two.canMove && two.canDelete)
        assertFalse(two.canRename)
        assertEquals("Rename works on one at a time", two.hint)
    }

    @Test
    fun `nothing is offered on the demo library, and the pill says why`() = runTest {
        val demo = share("DEMO NAS", DemoSource.HOST, "media")
        val demoRoot = db.folderDao().upsert(FolderEntity(shareId = demo, parentId = null, relPath = "", name = "media", fileCount = 0, byteCount = 0, lastListedAtMs = 1)).id
        val actions = actions()
        pick(film("Arrival.mkv", folderId = folder("Films", demoRoot, share = demo), share = demo))

        val verbs = actions.awaitState { it.verbs.hint != null }.verbs
        assertFalse(verbs.canMove || verbs.canRename || verbs.canDelete)
        assertEquals("The demo library can't be changed", verbs.hint)
    }

    @Test
    fun `picks on two shares can be deleted but not moved`() = runTest {
        val other = share("NAS2", "nas2", "films")
        val otherRoot = db.folderDao().upsert(FolderEntity(shareId = other, parentId = null, relPath = "", name = "films", fileCount = 0, byteCount = 0, lastListedAtMs = 1)).id
        val actions = actions()
        pick(film("Heat.mkv"))
        pick(film("Dune.mkv", folderId = folder("New", otherRoot, share = other), folderPath = "New", share = other))

        val verbs = actions.awaitState { it.verbs.hint == "Move works within one share" }.verbs
        assertFalse(verbs.canMove)
        assertTrue(verbs.canDelete)
    }

    // ── the move sheet ─────────────────────────────────────────────────

    @Test
    fun `with no folder on screen, the sheet opens at the top of the picks' share`() = runTest {
        val actions = actions()
        pick(film("Heat.mkv"))
        actions.awaitState { it.verbs.canMove }

        actions.startMove(here = null)

        val sheet = actions.awaitState { it.moveSheet != null }.moveSheet!!
        assertEquals(rootId, sheet.currentFolderId)
        assertEquals(listOf("Archive", "Films"), sheet.children.map { it.name })
    }

    @Test
    fun `a folder is only "already here" when every pick is in it`() = runTest {
        val actions = actions()
        pick(film("Heat.mkv"))
        actions.awaitState { it.verbs.canMove }
        actions.startMove(here = filmsId)
        val alone = actions.awaitState { it.moveSheet != null }.moveSheet!!
        assertEquals("They're already here", alone.note)
        assertFalse(alone.confirmEnabled)
        actions.dismissMove()

        pick(film("Old.mkv", folderId = archiveId, folderPath = "Archive"))
        actions.startMove(here = filmsId)
        val mixed = actions.awaitState { it.moveSheet != null }.moveSheet!!
        assertNull(mixed.note)
        assertTrue("the Archive pick can still come to Films", mixed.confirmEnabled)
    }

    @Test
    fun `undo puts each item back in the folder it came from`() = runTest {
        val kidsId = folder("Kids", rootId)
        val heat = film("Heat.mkv")
        val old = film("Old.mkv", folderId = archiveId, folderPath = "Archive")
        val actions = actions()
        pick(heat)
        pick(old)
        actions.awaitState { it.verbs.canMove }
        actions.startMove(here = filmsId)
        actions.awaitState { it.moveSheet != null }
        actions.moveChoose(kidsId)
        actions.awaitState { it.moveSheet?.chosenFolderId == kidsId }

        actions.confirmMove()
        val moved = actions.awaitState { it.message != null }.message!!
        assertEquals("Moved 2 videos to Kids", moved.text)
        assertTrue(onShare("Kids/Heat.mkv") && onShare("Kids/Old.mkv"))
        assertNull("a move leaves selection mode", selection.snapshot())

        actions.undoMove()
        assertEquals("Moved back 2 videos", actions.awaitState { it.message?.text?.startsWith("Moved back") == true }.message!!.text)
        assertTrue("Heat went back to Films", onShare("Films/Heat.mkv"))
        assertTrue("Old went back to Archive, not to Films", onShare("Archive/Old.mkv"))
        assertEquals(filmsId, db.mediaFileDao().byId(heat.id)!!.folderId)
        assertEquals(archiveId, db.mediaFileDao().byId(old.id)!!.folderId)
    }

    // ── rename and delete ──────────────────────────────────────────────

    @Test
    fun `rename asks with the current name, renames on the share and leaves selection mode`() = runTest {
        val heat = film("Heat.1995.mkv")
        val actions = actions()
        pick(heat)
        actions.awaitState { it.verbs.canRename }

        actions.startRename()
        assertEquals("Heat.1995.mkv", actions.awaitState { it.renaming != null }.renaming!!.name)
        actions.rename("Heat (1995)")

        assertEquals("Renamed 1 video", actions.awaitState { it.message != null }.message!!.text)
        assertTrue(onShare("Films/Heat (1995).mkv"))
        assertNull(selection.snapshot())
    }

    @Test
    fun `delete refuses while a picked folder has something taken back out`() = runTest {
        val heat = film("Heat.mkv")
        val actions = actions()
        pickFolder(filmsId, "Films")
        pick(heat) // inside the picked folder, so this takes it back out
        actions.awaitState { it.verbs.canDelete }

        actions.startDelete()

        val message = actions.awaitState { it.message != null }.message!!
        assertTrue(message.failed)
        assertTrue(message.text.startsWith("You un-picked something inside “Films”"))
        assertNull(actions.state.value.confirmingDelete)
        assertTrue(onShare("Films/Heat.mkv"))
    }

    @Test
    fun `the delete dialog counts through a picked folder`() = runTest {
        film("Heat.mkv")
        film("Sicario.mkv")
        val actions = actions()
        pickFolder(filmsId, "Films")
        actions.awaitState { it.verbs.canDelete }

        actions.startDelete()

        val target = actions.awaitState { it.confirmingDelete != null }.confirmingDelete!!
        assertEquals(listOf("Films"), target.names)
        assertEquals(2, target.videoCount)
        assertEquals(1, target.folderCount)
    }

    /** The reads FileActions makes, straight off the DAOs. */
    private class DaoLookup(private val db: RegolithDatabase) : FolderLookup {
        override suspend fun folder(folderId: Long) = db.folderDao().byId(folderId)
        override suspend fun file(fileId: Long) = db.mediaFileDao().byId(fileId)
        override suspend fun filesUnder(folderId: Long): List<MediaFileEntity> {
            val out = mutableListOf<MediaFileEntity>()
            var frontier = listOf(folderId)
            while (frontier.isNotEmpty()) {
                out += frontier.flatMap { db.mediaFileDao().inFolder(it) }
                frontier = frontier.flatMap { db.folderDao().children(it) }.map { it.id }
            }
            return out
        }
        override suspend fun subfolders(folderId: Long) = db.folderDao().children(folderId).sortedBy { it.name.lowercase() }
        override suspend fun rootFolder(shareId: Long): FolderEntity {
            val name = db.shareDao().byId(shareId)!!.name
            return db.folderDao().upsert(FolderEntity(shareId = shareId, parentId = null, relPath = "", name = name, fileCount = 0, byteCount = 0, lastListedAtMs = null))
        }
        override suspend fun shareLabel(shareId: Long) = "TOWER · ${db.shareDao().byId(shareId)!!.name}"
        override suspend fun companionCount(fileIds: Collection<Long>): Int =
            db.mediaFileDao().byIds(fileIds.toList()).groupBy { it.folderId }.entries.sumOf { (folderId, going) ->
                val names = db.mediaFileDao().inFolder(folderId).map { it.name } + db.shareFileDao().inFolder(folderId).map { it.name }
                Companions.goingWith(going.map { it.name }, names).size
            }
    }
}
