package com.regolith.domain.library

import java.time.Instant
import java.time.ZoneId

/** Which way a [LibrarySort] runs. */
enum class SortDirection {
    ASCENDING,
    DESCENDING,
    ;

    fun flipped(): SortDirection = if (this == ASCENDING) DESCENDING else ASCENDING
}

/**
 * One row of a sort sheet: what it is called, which way it naturally runs,
 * and both directions in words. [LibrarySort] and [MomentSort] are both, so
 * one sheet draws whichever list is on screen.
 */
interface SortChoice {
    val label: String
    val natural: SortDirection

    /** "Newest first", "Z to A": the direction as someone reading the list would say it. */
    fun directionLabel(direction: SortDirection): String
}

/**
 * What the poster wall is ordered by (design section 05, "Sort by"), and
 * the videos on the device tab, which keep an order of their own.
 *
 * Each criterion has a [natural] direction, the one you almost always mean
 * (names A to Z, everything else biggest or newest first), and says both
 * directions in words, because "ascending" means nothing next to "Date
 * added" until you have worked out which end is the old one.
 */
enum class LibrarySort(
    override val label: String,
    override val natural: SortDirection,
    private val ascendingLabel: String,
    private val descendingLabel: String,
) : SortChoice {
    NAME("Name", SortDirection.ASCENDING, "A to Z", "Z to A"),

    /**
     * When it arrived. On the wall that is the day a scan first found it,
     * because a scan stamps everything it finds at once: a library scanned
     * in one go arrived all together, and the file's own date orders it
     * instead (see [comparator]).
     */
    DATE_ADDED("Date added", SortDirection.DESCENDING, "Oldest first", "Newest first"),
    FILE_SIZE("File size", SortDirection.DESCENDING, "Smallest first", "Largest first"),
    RUNTIME("Runtime", SortDirection.DESCENDING, "Shortest first", "Longest first"),
    RESOLUTION("Resolution", SortDirection.DESCENDING, "Lowest first", "Highest first"),
    ;

    override fun directionLabel(direction: SortDirection): String =
        if (direction == SortDirection.ASCENDING) ascendingLabel else descendingLabel
}

/**
 * The wall's order, or the device tab's: a criterion and which way it
 * runs. Remembered in preferences, like a sortable column in a web table.
 */
data class LibraryOrder(
    val sort: LibrarySort = LibrarySort.NAME,
    val direction: SortDirection = sort.natural,
) {
    /**
     * What picking [next] in the sort sheet does: the one already in use
     * reverses, anything else starts in its natural direction. The same
     * rule as clicking a table header.
     */
    fun pick(next: LibrarySort): LibraryOrder =
        if (next == sort) copy(direction = direction.flipped()) else LibraryOrder(next)

    companion object {
        /** Where the device tab starts: the newest arrival first, which is how the tab always read. */
        val DEVICE_DEFAULT = LibraryOrder(LibrarySort.DATE_ADDED)
    }
}

/**
 * What a [LibraryOrder] reads off a poster or a row to place it: a wall's
 * tiles and the device tab's rows both carry these.
 */
interface SortKeys {
    val name: String

    /** When it arrived: when a scan first found it on the share, or when a copy landed on this phone. */
    val addedAtMs: Long

    /** The file's own date, its last write where it lives. Settles two that arrived together. */
    val fileDateMs: Long
    val sizeBytes: Long

    /** Null until the file has been measured. */
    val durationMs: Long?

    /** Null until the file has been measured. */
    val height: Int?
}

/**
 * This order as a comparator, for a list of anything with [SortKeys].
 *
 * Names break every tie, so the order is total and two lists with the same
 * things in them always come out the same. A runtime or a resolution not
 * measured yet goes LAST in both directions: reversing the sort should not
 * float the unknowns to the top.
 *
 * [addedByDay] compares "Date added" to the day, and lets the file's own
 * date order whatever arrived on the same one. The wall passes it, because
 * a scan stamps the moment it found each file: everything the first scan
 * found shares a moment (to the millisecond within a folder), and ordering
 * by that would only replay the order the scan walked in. A copy on the
 * phone landed when it landed, so the device tab compares exact times.
 */
fun <T : SortKeys> LibraryOrder.comparator(addedByDay: Boolean = false, zone: ZoneId = ZoneId.systemDefault()): Comparator<T> {
    val byName = compareBy<T, String>(String.CASE_INSENSITIVE_ORDER) { it.name }
    val primary: Comparator<T> = when (sort) {
        LibrarySort.NAME -> byName
        LibrarySort.DATE_ADDED -> compareBy<T> { if (addedByDay) Instant.ofEpochMilli(it.addedAtMs).atZone(zone).toLocalDate().toEpochDay() else it.addedAtMs }
            .thenBy { it.fileDateMs }
            .then(byName)
        LibrarySort.FILE_SIZE -> compareBy<T> { it.sizeBytes }.then(byName)
        LibrarySort.RUNTIME -> compareBy<T> { it.durationMs }.then(byName)
        LibrarySort.RESOLUTION -> compareBy<T> { it.height }.then(byName)
    }
    val unmeasured: (T) -> Boolean = when (sort) {
        LibrarySort.RUNTIME -> { item -> item.durationMs == null }
        LibrarySort.RESOLUTION -> { item -> item.height == null }
        else -> { _ -> false }
    }
    val directed = if (direction == SortDirection.ASCENDING) primary else primary.reversed()
    return compareBy(unmeasured).then(directed)
}
