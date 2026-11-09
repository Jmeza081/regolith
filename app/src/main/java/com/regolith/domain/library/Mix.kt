package com.regolith.domain.library

/**
 * A **mix** is a folder of videos that have nothing to do with each other
 * (`Assorted/`), as opposed to a collection that is one story (`Band
 * Practice/`, a concert). The owner marks it by hand; the app never guesses,
 * because "has no poster" is a guess about what a folder LOOKS like, not
 * what it IS (docs/MIX_PLAN.md).
 *
 * The mark lives on the share, the way `poster.jpg` does: an empty file
 * named [NAME] inside the folder. It survives a reinstall, works from any
 * phone, and moves and renames with the folder because it is in it. The dot
 * keeps it out of Browse and off a Mac's Finder; the writer also sets the
 * SMB hidden attribute so Windows Explorer hides it too.
 */
object MixMarker {
    /** The marker file's name, inside the folder it marks. */
    const val NAME = ".regolith-mix"

    /** Whether a folder whose direct entries are [names] is marked as a mix. */
    fun foundIn(names: Collection<String>): Boolean = NAME in names

    /** The marker's path in the folder at [folderRelPath] (`""` for the share root). */
    fun pathIn(folderRelPath: String): String = if (folderRelPath.isEmpty()) NAME else "$folderRelPath/$NAME"
}

/** How marking (or unmarking) a folder as a mix went, in the words the screen needs to pick a message. */
enum class MixOutcome {
    DONE,

    /** The share or the account will not take writes. */
    READ_ONLY,

    /** The server could not be reached. */
    UNREACHABLE,

    /** Nowhere to write: the folder is gone, or it is on the phone or the demo library. */
    FAILED,
}

/**
 * Which part of a wall that holds both shows: the chips under Videos
 * (docs/MIX_PLAN.md). [ALL] is the split view, collections first and then
 * the mixes, each under its own heading. Remembered across launches.
 */
enum class MixFilter(val label: String) {
    ALL("All"),
    COLLECTIONS("Collections"),
    MIXES("Mixes"),
}

/**
 * A wall split by [MixFilter].
 *
 * @property items what the wall shows, in order: the collections (and any
 *   videos lying loose beside them), then the mixes.
 * @property mixStart where the Mixes heading goes in [items], when the wall
 *   is split into both sections; null when it shows one kind only.
 * @property split how many of each the wall holds, when it holds both, for
 *   the chips. Null when there is nothing to choose between (no mixes, or
 *   nothing but mixes): no chips, no headings, the wall as it always was.
 */
data class MixSections<T>(val items: List<T>, val mixStart: Int?, val split: MixSplit?)

/** How many tiles of each kind a wall holds: the numbers on the chips. */
data class MixSplit(val collections: Int, val mixes: Int)

/**
 * Split an already-sorted wall by [filter]. Each section keeps the wall's
 * own order; [isMix] says which items are mixes.
 */
fun <T> List<T>.byMix(filter: MixFilter, isMix: (T) -> Boolean): MixSections<T> {
    val (mixes, rest) = partition(isMix)
    if (mixes.isEmpty() || rest.isEmpty()) return MixSections(this, mixStart = null, split = null)
    val split = MixSplit(collections = rest.size, mixes = mixes.size)
    return when (filter) {
        MixFilter.ALL -> MixSections(rest + mixes, mixStart = rest.size, split = split)
        MixFilter.COLLECTIONS -> MixSections(rest, mixStart = null, split = split)
        MixFilter.MIXES -> MixSections(mixes, mixStart = null, split = split)
    }
}
