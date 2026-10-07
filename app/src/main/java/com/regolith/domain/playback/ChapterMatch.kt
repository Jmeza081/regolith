package com.regolith.domain.playback

import java.text.Collator
import java.util.Locale

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
    /** When the chapter was named or last renamed: a profile's Moments tab sorts by it. */
    val namedAtMs: Long = 0,
)

/**
 * A point of interest that recurs across the library: a chapter name and
 * the number of films carrying it. Search offers these as filter chips,
 * commonest first; Home lists every one of them, [alphabetical].
 */
data class ChapterFacet(val title: String, val films: Int)

/**
 * These names A to Z the way a person reads a list, not the way the
 * database compares bytes: capitals and accents don't move a name
 * ("éclair" sits with the e's, "Piñata" with the p's). SQLite's `NOCASE`
 * folds ASCII only, which is why the order is made here rather than in the
 * query. Names that collate alike still keep one fixed order between them,
 * so chips never swap places from one refresh to the next.
 */
fun List<ChapterFacet>.alphabetical(locale: Locale = Locale.getDefault()): List<ChapterFacet> {
    val collator = Collator.getInstance(locale).apply { strength = Collator.PRIMARY }
    return sortedWith(compareBy<ChapterFacet, String>(collator) { it.title }.thenBy { it.title })
}

/** How much the user has written, for Settings › Chapters. */
data class UserChapterStats(
    val chapters: Int = 0,
    /** Distinct films with at least one row. */
    val files: Int = 0,
) {
    val isEmpty: Boolean get() = chapters == 0
}
