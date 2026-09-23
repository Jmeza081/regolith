package com.regolith.domain.artwork

/**
 * Where a poster made in the editor will go, worked out before anything is
 * written. Null from the repository means there is nowhere to put one (a
 * video on the phone, the demo library, a film another app handed us).
 */
data class PosterTarget(
    /** The folder poster.jpg lands in, as the user knows it: "Arrival (2016)", or the share's name at its root. */
    val folderName: String,
    /**
     * True when the folder holds other videos too. Then poster.jpg is that
     * folder's poster, not this film's (see [ArtworkCandidates.forFile]), and
     * the editor says so.
     */
    val sharedWithFolder: Boolean,
)

/** How a save went, in the words the editor needs to pick a message. */
enum class PosterSaveOutcome {
    SAVED,

    /** poster.jpg is already there and the user has not said to replace it. */
    EXISTS,

    /** The share or the account will not take writes. */
    READ_ONLY,

    /** The server could not be reached. */
    UNREACHABLE,

    FAILED,
}
