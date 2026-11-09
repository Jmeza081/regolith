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
