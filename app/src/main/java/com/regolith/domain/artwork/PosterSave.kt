package com.regolith.domain.artwork

/**
 * Where a poster made in the editor will go, worked out before anything is
 * written. Null from the repository means there is nowhere to put one (a
 * video on the phone, the demo library, a film another app handed us).
 *
 * It is always the poster of the film's folder, `poster.jpg` there: every
 * folder of videos is a collection, and its poster is the collection's, never
 * one video's (the owner's model, 2026-10-05). The editor says so.
 */
data class PosterTarget(
    /** The folder poster.jpg lands in, as the user knows it: "Arrival (2016)", or the share's name at its root. */
    val folderName: String,
)

/** How a save went, in the words the editor needs to pick a message. */
enum class PosterSaveOutcome {
    SAVED,

    /** The share or the account will not take writes. */
    READ_ONLY,

    /** The server could not be reached. */
    UNREACHABLE,

    FAILED,
}
