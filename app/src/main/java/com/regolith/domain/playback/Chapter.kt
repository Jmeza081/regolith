package com.regolith.domain.playback

/**
 * One chapter marker in a file: where it starts, and what it is called.
 *
 * Chapters are metadata inside the container, put there by whoever made
 * the file. Regolith reads them and never writes them, and a file that
 * has none simply has none — nothing is invented from scene detection.
 */
data class Chapter(
    val startMs: Long,
    /** Null when the container gives a time but no name. */
    val title: String?,
) {
    /** What the sheet shows: the name, or "Chapter n" when there isn't one. */
    fun label(index: Int): String = title?.takeIf { it.isNotBlank() } ?: "Chapter ${index + 1}"
}
