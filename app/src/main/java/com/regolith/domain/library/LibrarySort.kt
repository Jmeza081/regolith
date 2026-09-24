package com.regolith.domain.library

/** Which way a [LibrarySort] runs. */
enum class SortDirection {
    ASCENDING,
    DESCENDING,
    ;

    fun flipped(): SortDirection = if (this == ASCENDING) DESCENDING else ASCENDING
}

/**
 * What the poster wall is ordered by (design section 05, "Sort by").
 *
 * Each criterion has a [natural] direction, the one you almost always mean
 * (names A to Z, everything else biggest or newest first), and says both
 * directions in words, because "ascending" means nothing next to "Date
 * added" until you have worked out which end is the old one.
 */
enum class LibrarySort(
    val label: String,
    val natural: SortDirection,
    private val ascendingLabel: String,
    private val descendingLabel: String,
) {
    NAME("Name", SortDirection.ASCENDING, "A to Z", "Z to A"),
    DATE_ADDED("Date added", SortDirection.DESCENDING, "Oldest first", "Newest first"),
    FILE_SIZE("File size", SortDirection.DESCENDING, "Smallest first", "Largest first"),
    RUNTIME("Runtime", SortDirection.DESCENDING, "Shortest first", "Longest first"),
    RESOLUTION("Resolution", SortDirection.DESCENDING, "Lowest first", "Highest first"),
    ;

    /** "Newest first", "Z to A": the direction as someone reading the wall would say it. */
    fun directionLabel(direction: SortDirection): String =
        if (direction == SortDirection.ASCENDING) ascendingLabel else descendingLabel
}

/**
 * The wall's order: a criterion and which way it runs. Remembered in
 * preferences, like a sortable column in a web table.
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
}
