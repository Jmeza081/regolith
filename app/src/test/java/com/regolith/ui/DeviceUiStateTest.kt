package com.regolith.ui

import com.regolith.domain.transfer.TransferCause
import com.regolith.domain.transfer.TransferStatus
import com.regolith.ui.library.DeviceRow
import com.regolith.ui.library.DeviceUiState
import com.regolith.ui.library.RemoveTarget
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
