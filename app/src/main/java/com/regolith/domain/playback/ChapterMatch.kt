package com.regolith.domain.playback

/**
 * A chapter the user named, found by Search. It is a PLACE in a film, not
 * a file, which is why it carries the file's identity beside the chapter's:
 * the row shows the chapter name with the film under it, and a tap opens
 * the player at [startMs].
 */
data class ChapterMatch(
    val fileId: Long,
    val startMs: Long,
    /** Never null: only named chapters are indexed. */
    val title: String,
    /** The film's filename, for the title fallback. */
    val fileName: String,
    /** The film's parsed title, when the filename parser found one. */
    val fileTitle: String?,
    val shareId: Long,
    /** The file's own path in the share, `/`-separated, no leading slash. */
    val fileRelPath: String,
)

/** How much the user has written, for Settings › Chapters. */
data class UserChapterStats(
    val chapters: Int = 0,
    /** Distinct films with at least one row. */
    val files: Int = 0,
) {
    val isEmpty: Boolean get() = chapters == 0
}
