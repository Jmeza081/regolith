package com.regolith.domain.library

/**
 * What a collection profile's Moments tab is ordered by. Its own list
 * because a moment is not a file: it has no size or resolution, but it has
 * a name of its own and a day someone gave it that name.
 */
enum class MomentSort(
    override val label: String,
    override val natural: SortDirection,
    private val ascendingLabel: String,
    private val descendingLabel: String,
) : SortChoice {
    /**
     * Grouped by video, the videos in the order the Videos tab shows them,
     * each video's moments in the order they play. Reversing it reverses
     * the videos, never the moments within one.
     */
    VIDEO("Video", SortDirection.ASCENDING, "As the videos are sorted", "Videos reversed"),
    NAME("Name", SortDirection.ASCENDING, "A to Z", "Z to A"),

    /** When the moment was named, or last renamed. */
    DATE_NAMED("Date named", SortDirection.DESCENDING, "Oldest first", "Newest first"),
    ;

    override fun directionLabel(direction: SortDirection): String =
        if (direction == SortDirection.ASCENDING) ascendingLabel else descendingLabel
}

/** The Moments tab's order, remembered apart from the wall's. Picks like [LibraryOrder]. */
data class MomentOrder(
    val sort: MomentSort = MomentSort.VIDEO,
    val direction: SortDirection = sort.natural,
) {
    /** The one already in use reverses; anything else starts in its natural direction. */
    fun pick(next: MomentSort): MomentOrder =
        if (next == sort) copy(direction = direction.flipped()) else MomentOrder(next)
}
