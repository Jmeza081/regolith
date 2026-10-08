package com.regolith.domain.library

/**
 * What a collection's pictures are ordered by: its Images tab, and the
 * pictures lying loose on a wall. A list of its own because a picture is not
 * a video — it has no runtime, but it has the moment it was taken.
 */
enum class PictureSort(
    override val label: String,
    override val natural: SortDirection,
    private val ascendingLabel: String,
    private val descendingLabel: String,
) : SortChoice {
    /** When the camera took it, or its file's own date when it does not say. */
    DATE_TAKEN("Date taken", SortDirection.DESCENDING, "Oldest first", "Newest first"),
    NAME("Name", SortDirection.ASCENDING, "A to Z", "Z to A"),

    /** When a scan first found it on the share. */
    DATE_ADDED("Date added", SortDirection.DESCENDING, "Oldest first", "Newest first"),
    ;

    override fun directionLabel(direction: SortDirection): String =
        if (direction == SortDirection.ASCENDING) ascendingLabel else descendingLabel
}

/**
 * The pictures' order, remembered apart from the wall's: newest taken
 * first, the way a phone's gallery opens. Picks like [LibraryOrder].
 */
data class PictureOrder(
    val sort: PictureSort = PictureSort.DATE_TAKEN,
    val direction: SortDirection = sort.natural,
) {
    /** The one already in use reverses; anything else starts in its natural direction. */
    fun pick(next: PictureSort): PictureOrder =
        if (next == sort) copy(direction = direction.flipped()) else PictureOrder(next)
}

/** What a [PictureOrder] reads off a picture to place it. */
interface PictureKeys {
    val name: String

    /** When it was taken, when the picture says. */
    val takenAtMs: Long?

    /** Its file's date on the share. */
    val modifiedAtMs: Long

    /** When a scan first found it; null for one found before that was kept. */
    val addedAtMs: Long?
}

/**
 * This order as a comparator. Names break every tie, so two lists of the
 * same pictures always come out the same, and the lightbox and a story walk
 * them in exactly the order the tab shows.
 */
fun <T : PictureKeys> PictureOrder.comparator(): Comparator<T> {
    val byName = compareBy<T, String>(String.CASE_INSENSITIVE_ORDER) { it.name }
    val primary: Comparator<T> = when (sort) {
        PictureSort.DATE_TAKEN -> compareBy<T> { it.takenAtMs ?: it.modifiedAtMs }.then(byName)
        PictureSort.NAME -> byName
        PictureSort.DATE_ADDED -> compareBy<T> { it.addedAtMs ?: it.modifiedAtMs }.thenBy { it.takenAtMs ?: it.modifiedAtMs }.then(byName)
    }
    return if (direction == SortDirection.ASCENDING) primary else primary.reversed()
}
