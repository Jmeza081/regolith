package com.regolith.ui

import com.regolith.domain.transfer.TransferCause
import com.regolith.domain.transfer.TransferStatus
import com.regolith.ui.library.DeviceRow
import com.regolith.ui.library.DeviceUiState
import com.regolith.ui.library.RemoveTarget
import com.regolith.ui.library.withWall
import com.regolith.ui.library.withRows
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The On-this-device page's own selection, and the arithmetic behind the
 * confirm dialog. These matter more than most: every path here deletes
 * something, and the only way back is another download over SMB.
 */
class DeviceUiStateTest {

    private fun row(id: Long, status: TransferStatus, total: Long, done: Long = total) = DeviceRow(
        fileId = id, name = "f$id.mkv", status = status, cause = null, causeBytes = null,
        bytesDone = done, totalBytes = total, meta = "",
    )

    private val page = DeviceUiState(
        ready = listOf(row(1, TransferStatus.DONE, 2_000_000_000), row(2, TransferStatus.DONE, 3_000_000_000)),
        inFlight = listOf(row(3, TransferStatus.RUNNING, 10_000_000_000, done = 1_000_000_000)),
        failed = listOf(row(4, TransferStatus.FAILED, 5_000_000_000, done = 500_000_000)),
    )

    @Test
    fun `every row on the page can be selected, whatever its state`() {
        // Arriving and failed copies hold bytes too, so Clear all and Select
        // all have to reach them or the page lies about what it freed.
        assertEquals(listOf(1L, 2L, 3L, 4L), page.allFileIds)
    }

    @Test
    fun `a finished copy is counted at its full size`() {
        val state = page.copy(picked = setOf(1L, 2L))
        assertEquals(5_000_000_000, state.pickedBytes())
    }

    @Test
    fun `an unfinished copy is counted at what it has actually written`() {
        // Its totalBytes is what it WILL take; only bytesDone is on the disk
        // now, and the bar is promising space back, not space avoided.
        val state = page.copy(picked = setOf(3L, 4L))
        assertEquals(1_500_000_000, state.pickedBytes())
    }

    @Test
    fun `nothing picked frees nothing`() {
        assertEquals(0, page.copy(picked = emptySet()).pickedBytes())
        assertEquals(0, page.pickedBytes())
    }

    @Test
    fun `a picked id that is no longer on the page is ignored`() {
        // A copy can finish or fail out from under a live selection.
        val state = page.copy(picked = setOf(1L, 99L))
        assertEquals(2_000_000_000, state.pickedBytes())
    }

    // ── what the dialog is about ────────────────────────────────────────

    @Test
    fun `the dialog counts the picked copies when that is what it asked about`() {
        val state = page.copy(picked = setOf(1L, 3L), confirmRemove = RemoveTarget.PICKED)
        assertEquals(2, state.confirmCount)
    }

    @Test
    fun `the dialog counts the whole page for Clear all`() {
        val state = page.copy(confirmRemove = RemoveTarget.EVERYTHING)
        assertEquals(4, state.confirmCount)
    }

    @Test
    fun `Clear all counts the page even while a selection is open`() {
        // The two are different acts and the count has to say which one is
        // about to happen — this is the misread that would delete a library.
        val state = page.copy(picked = setOf(1L), confirmRemove = RemoveTarget.EVERYTHING)
        assertEquals(4, state.confirmCount)
    }

    @Test
    fun `a closed dialog counts nothing`() {
        assertEquals(0, page.copy(picked = setOf(1L, 2L)).confirmCount)
    }

    @Test
    fun `an empty selection asked about is zero, not everything`() {
        // The guard that matters: PICKED with nothing picked must never read
        // as "all of it".
        val state = page.copy(picked = emptySet(), confirmRemove = RemoveTarget.PICKED)
        assertEquals(0, state.confirmCount)
    }

    @Test
    fun `an empty page has nothing to clear`() {
        val empty = DeviceUiState(confirmRemove = RemoveTarget.EVERYTHING)
        assertEquals(0, empty.confirmCount)
        assertEquals(emptyList<Long>(), empty.allFileIds)
    }

    @Test
    fun `a failed copy keeps its cause available for the row's own line`() {
        val failed = row(9, TransferStatus.FAILED, 1, 0).copy(cause = TransferCause.NO_ROOM, causeBytes = 12_100_000_000)
        assertEquals(TransferCause.NO_ROOM, failed.cause)
    }
}

/**
 * The state merges, which are where a selection is most easily lost.
 *
 * Room re-emits the wall constantly and an in-flight download re-emits
 * every 500 ms. If installing a fresh build reset anything the user was in
 * the middle of, no selection could survive long enough to be used — which
 * is precisely the bug these guard against.
 */
class LibraryStateMergeTest {

    private fun tile(id: Long) = com.regolith.ui.library.LibraryTile.Title(
        fileId = id, name = "t$id", resolutionLabel = "", matched = true, unwatched = false, fileName = "t$id.mkv",
        progress = null, meta = "", artwork = com.regolith.domain.artwork.ArtworkRequest(
            com.regolith.domain.artwork.ArtworkOwner.File(id), com.regolith.domain.artwork.ArtworkKind.POSTER,
        ),
        addedAtMs = 0, sizeBytes = 0, durationMs = null, height = null,
    )

    @Test
    fun `a fresh wall does not wipe a live selection`() {
        // The exact failure: hold a tile, the wall re-emits, the selection is gone.
        val live = com.regolith.ui.library.LibraryUiState(
            selection = com.regolith.ui.util.SelectionUiState(itemCount = 3, fileCount = 3),
            sortSheetOpen = true,
        )
        val built = com.regolith.ui.library.LibraryUiState(title = "Severance", tiles = listOf(tile(1)), loaded = true)
        val merged = live.withWall(built, built.tiles)
        assertEquals(3, merged.selection?.itemCount)
        assertEquals(true, merged.sortSheetOpen)
        // and the build itself did land
        assertEquals("Severance", merged.title)
        assertEquals(listOf(1L), merged.tiles.map { (it as com.regolith.ui.library.LibraryTile.Title).fileId })
        assertEquals(true, merged.loaded)
    }

    @Test
    fun `a fresh wall does not wipe the device selection either`() {
        val live = com.regolith.ui.library.LibraryUiState(
            device = DeviceUiState(picked = setOf(4L, 5L), confirmRemove = RemoveTarget.PICKED),
        )
        val merged = live.withWall(com.regolith.ui.library.LibraryUiState(title = "x"), emptyList())
        assertEquals(setOf(4L, 5L), merged.device.picked)
        assertEquals(RemoveTarget.PICKED, merged.device.confirmRemove)
    }

    @Test
    fun `a progress tick does not wipe a device selection mid-use`() {
        val live = DeviceUiState(
            ready = listOf(row(1, TransferStatus.DONE, 10)),
            picked = setOf(1L),
            confirmRemove = RemoveTarget.EVERYTHING,
            showAllFailed = true,
        )
        // The download moved a few bytes; the rows are rebuilt.
        val built = DeviceUiState(
            ready = listOf(row(1, TransferStatus.DONE, 10)),
            inFlight = listOf(row(2, TransferStatus.RUNNING, 100, done = 50)),
            usedBytes = 60,
            totalBytes = 1000,
        )
        val merged = live.withRows(built)
        assertEquals(setOf(1L), merged.picked)
        assertEquals(RemoveTarget.EVERYTHING, merged.confirmRemove)
        assertEquals(true, merged.showAllFailed)
        // and the rows themselves are the new ones
        assertEquals(listOf(2L), merged.inFlight.map { it.fileId })
        assertEquals(60L, merged.usedBytes)
    }

    @Test
    fun `merging onto a resting state changes nothing it should not`() {
        val merged = com.regolith.ui.library.LibraryUiState().withWall(com.regolith.ui.library.LibraryUiState(title = "x"), emptyList())
        assertEquals(null, merged.selection)
        assertEquals(null, merged.device.picked)
    }

    private fun row(id: Long, status: TransferStatus, total: Long, done: Long = total) = DeviceRow(
        fileId = id, name = "f$id.mkv", status = status, cause = null, causeBytes = null,
        bytesDone = done, totalBytes = total, meta = "",
    )
}
