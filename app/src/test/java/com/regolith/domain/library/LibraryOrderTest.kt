package com.regolith.domain.library

import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryOrderTest {
    @Test fun `a new criterion starts in its natural direction`() {
        assertEquals(LibraryOrder(LibrarySort.NAME, SortDirection.ASCENDING), LibraryOrder())
        assertEquals(SortDirection.DESCENDING, LibraryOrder().pick(LibrarySort.DATE_ADDED).direction)
        assertEquals(SortDirection.ASCENDING, LibraryOrder(LibrarySort.RUNTIME).pick(LibrarySort.NAME).direction)
    }

    @Test fun `picking the one in use reverses it, and again puts it back`() {
        val newest = LibraryOrder(LibrarySort.DATE_ADDED)
        val oldest = newest.pick(LibrarySort.DATE_ADDED)
        assertEquals(LibraryOrder(LibrarySort.DATE_ADDED, SortDirection.ASCENDING), oldest)
        assertEquals(newest, oldest.pick(LibrarySort.DATE_ADDED))
    }

    @Test fun `a reversed criterion forgets its direction when you move away`() {
        val zToA = LibraryOrder(LibrarySort.NAME, SortDirection.DESCENDING)
        assertEquals(LibraryOrder(LibrarySort.NAME), zToA.pick(LibrarySort.FILE_SIZE).pick(LibrarySort.NAME))
    }

    @Test fun `every criterion says both directions in words`() {
        assertEquals("Newest first", LibrarySort.DATE_ADDED.directionLabel(SortDirection.DESCENDING))
        assertEquals("Z to A", LibrarySort.NAME.directionLabel(SortDirection.DESCENDING))
        (LibrarySort.entries + MomentSort.entries).forEach {
            assert(it.directionLabel(SortDirection.ASCENDING) != it.directionLabel(SortDirection.DESCENDING))
        }
    }

    @Test fun `the moments' order picks the same way, starting from their videos`() {
        assertEquals(MomentOrder(MomentSort.VIDEO, SortDirection.ASCENDING), MomentOrder())
        assertEquals(SortDirection.DESCENDING, MomentOrder().pick(MomentSort.DATE_NAMED).direction)
        assertEquals(MomentOrder(MomentSort.NAME, SortDirection.DESCENDING), MomentOrder(MomentSort.NAME).pick(MomentSort.NAME))
    }

    private data class Item(
        override val name: String,
        override val addedAtMs: Long = 0,
        override val fileDateMs: Long = 0,
        override val sizeBytes: Long = 0,
        override val durationMs: Long? = null,
        override val height: Int? = null,
    ) : SortKeys

    private val utc = ZoneId.of("UTC")
    private fun at(day: Int, hour: Int) = (day * 24L + hour) * 3_600_000L

    // Found by the first scan on day 1, half a second apart, with files of their
    // own from different years; then one more found by a scan on day 2.
    private val oldClip = Item("Old clip", addedAtMs = at(1, 10) + 500, fileDateMs = at(-3000, 0))
    private val newClip = Item("New clip", addedAtMs = at(1, 10), fileDateMs = at(-100, 0))
    private val arrival = Item("Arrival", addedAtMs = at(2, 9), fileDateMs = at(-5000, 0))

    @Test fun `on the wall, date added goes by the day found and then the file's own date`() {
        val newest = LibraryOrder(LibrarySort.DATE_ADDED).comparator<Item>(addedByDay = true, zone = utc)
        assertEquals(listOf(arrival, newClip, oldClip), listOf(oldClip, arrival, newClip).sortedWith(newest))
        val oldest = LibraryOrder(LibrarySort.DATE_ADDED, SortDirection.ASCENDING).comparator<Item>(addedByDay = true, zone = utc)
        assertEquals(listOf(oldClip, newClip, arrival), listOf(arrival, newClip, oldClip).sortedWith(oldest))
    }

    @Test fun `on the device, date added is the moment it landed`() {
        val newest = LibraryOrder(LibrarySort.DATE_ADDED).comparator<Item>(zone = utc)
        assertEquals(listOf(arrival, oldClip, newClip), listOf(newClip, arrival, oldClip).sortedWith(newest))
    }

    @Test fun `a tie on every key falls back to the name, both ways round`() {
        val a = Item("apple", sizeBytes = 5)
        val b = Item("Banana", sizeBytes = 5)
        assertEquals(listOf(b, a), listOf(a, b).sortedWith(LibraryOrder(LibrarySort.FILE_SIZE).comparator()))
        assertEquals(listOf(a, b), listOf(b, a).sortedWith(LibraryOrder(LibrarySort.FILE_SIZE, SortDirection.ASCENDING).comparator()))
    }

    @Test fun `what has not been measured goes last in both directions`() {
        val short = Item("Short", durationMs = 1_000)
        val long = Item("Long", durationMs = 9_000)
        val unknown = Item("Unknown")
        assertEquals(listOf(long, short, unknown), listOf(unknown, short, long).sortedWith(LibraryOrder(LibrarySort.RUNTIME).comparator()))
        assertEquals(listOf(short, long, unknown), listOf(unknown, long, short).sortedWith(LibraryOrder(LibrarySort.RUNTIME, SortDirection.ASCENDING).comparator()))
    }
}
