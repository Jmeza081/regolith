package com.regolith.ui.library

import com.regolith.domain.library.LibraryOrder
import com.regolith.domain.library.LibrarySort
import com.regolith.domain.library.SortDirection
import com.regolith.domain.transfer.TransferStatus
import org.junit.Assert.assertEquals
import org.junit.Test

/** The device tab sorts its own lists: the copies, each phone folder, and the folders themselves. */
class DeviceOrderTest {

    private fun row(id: Long, name: String, addedAtMs: Long = 0, sizeBytes: Long = 0, phone: Boolean = false) = DeviceRow(
        fileId = id, name = name, status = TransferStatus.DONE, cause = null, causeBytes = null,
        bytesDone = sizeBytes, totalBytes = sizeBytes, meta = "", phone = phone, addedAtMs = addedAtMs,
    )

    private fun folder(id: Long, name: String, vararg videos: DeviceRow) =
        PhoneFolder(folderId = id, relPath = name, name = name, path = name, videos = videos.toList())

    private val camera = folder(1, "Camera", row(10, "Beach", addedAtMs = 300, sizeBytes = 5, phone = true), row(11, "Birthday", addedAtMs = 100, sizeBytes = 90, phone = true))
    private val movies = folder(2, "Movies", row(20, "Airshow", addedAtMs = 200, sizeBytes = 40, phone = true))
    private val state = DeviceUiState(
        ready = listOf(row(1, "Arrival", addedAtMs = 50, sizeBytes = 70), row(2, "Heat", addedAtMs = 400, sizeBytes = 10)),
        phoneFolders = listOf(movies, camera),
    )

    @Test
    fun `it starts newest first, as the tab always read`() {
        val sorted = state.inOrder()
        assertEquals(LibraryOrder.DEVICE_DEFAULT, sorted.order)
        assertEquals(listOf("Heat", "Arrival"), sorted.ready.map { it.name })
        // Camera holds the newest phone video, so it leads, its newest first.
        assertEquals(listOf("Camera", "Movies"), sorted.phoneFolders.map { it.name })
        assertEquals(listOf("Beach", "Birthday"), sorted.phoneFolders.first().videos.map { it.name })
    }

    @Test
    fun `by size, the folder holding the largest video comes first`() {
        val sorted = state.inOrder(LibraryOrder(LibrarySort.FILE_SIZE))
        assertEquals(listOf("Arrival", "Heat"), sorted.ready.map { it.name })
        assertEquals(listOf("Camera", "Movies"), sorted.phoneFolders.map { it.name })
        assertEquals(listOf("Birthday", "Beach"), sorted.phoneFolders.first().videos.map { it.name })
        val smallest = state.inOrder(LibraryOrder(LibrarySort.FILE_SIZE, SortDirection.ASCENDING))
        assertEquals(listOf("Camera", "Movies"), smallest.phoneFolders.map { it.name })
        assertEquals(listOf("Beach", "Birthday"), smallest.phoneFolders.first().videos.map { it.name })
    }

    @Test
    fun `by name, the folders go by their own names`() {
        val zToA = state.inOrder(LibraryOrder(LibrarySort.NAME, SortDirection.DESCENDING))
        assertEquals(listOf("Movies", "Camera"), zToA.phoneFolders.map { it.name })
        assertEquals(listOf("Heat", "Arrival"), zToA.ready.map { it.name })
    }

    @Test
    fun `copies that arrive later land in the order already in force`() {
        val bySize = state.inOrder(LibraryOrder(LibrarySort.FILE_SIZE))
        val arrived = bySize.withRows(DeviceUiState(ready = listOf(row(3, "Dune", sizeBytes = 50), row(2, "Heat", sizeBytes = 10), row(1, "Arrival", sizeBytes = 70))))
        assertEquals(listOf("Arrival", "Dune", "Heat"), arrived.ready.map { it.name })
    }
}
