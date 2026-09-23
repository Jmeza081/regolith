package com.regolith.domain.media

import com.regolith.domain.playback.Chapter
import java.util.Locale

/**
 * The chapter sidecar (P10): `<basename>.chapters.txt` beside a film on
 * the share, in mkvmerge's "simple" chapter format — two lines per
 * chapter, a timestamp and a name:
 *
 * ```
 * CHAPTER01=00:00:00.000
 * CHAPTER01NAME=Intro
 * CHAPTER02=00:12:30.500
 * CHAPTER02NAME=
 * ```
 *
 * Chosen because anyone can write it in a text editor, a desktop app can
 * write it without knowing anything about the phone, and
 * `mkvpropedit --chapters` can bake it into the MKV later. The file is
 * the contract; `docs/CHAPTERS.md` is its specification.
 *
 * Reading is forgiving — a file on a share is untrusted input: lines that
 * do not match are skipped, order does not matter, tenths are the finest
 * resolution kept, the input is capped, and a missing 0:00 is added.
 * Writing is exact: sorted, zero-padded, LF, trailing newline. Pure.
 */
object ChapterSidecar {
    /** The file that belongs to `Heat.1995.mkv` is `Heat.1995.chapters.txt`. */
    const val SUFFIX = ".chapters.txt"

    /** More than this is not a chapter file. */
    const val MAX_BYTES = 64 * 1024

    /** Matches the container parser's ceiling. */
    const val MAX_CHAPTERS = 999

    /** The sidecar name for a video's filename, or null when the name is not a video's. */
    fun sidecarNameFor(videoName: String): String? {
        if (!MediaFileTypes.isVideo(videoName)) return null
        return videoName.substringBeforeLast('.') + SUFFIX
    }

    /** The video basename a sidecar name belongs to (`Heat.1995` for `Heat.1995.chapters.txt`), or null. */
    fun basenameOf(sidecarName: String): String? =
        if (sidecarName.endsWith(SUFFIX) && sidecarName.length > SUFFIX.length) sidecarName.dropLast(SUFFIX.length) else null

    /**
     * Parse a file's text. Chapters past [durationMs] are dropped when the
     * runtime is known (pass 0 to keep everything). A name that is empty,
     * blank, or exactly the "Part n" the app would have shown anyway is
     * read as no name, so the sheet keeps its arithmetic label and Search
     * does not index it.
     */
    fun parse(text: String, durationMs: Long = 0): List<Chapter> {
        val starts = mutableMapOf<Int, Long>()
        val names = mutableMapOf<Int, String>()
        for (raw in text.lineSequence().take(MAX_CHAPTERS * 2 + 16)) {
            val line = raw.trimEnd('\r').trim()
            val m = LINE.matchEntire(line) ?: continue
            val index = m.groupValues[1].toIntOrNull() ?: continue
            val isName = m.groupValues[2].isNotEmpty()
            val value = m.groupValues[3]
            if (isName) names[index] = value.trim()
            else parseTimestamp(value)?.let { starts[index] = it }
        }
        val found = starts.entries.sortedBy { it.key }
            .map { (index, ms) -> Chapter(ms, names[index]?.takeUnless { it.isEmpty() || it.equals("Part $index", ignoreCase = true) }) }
            .filter { durationMs <= 0 || it.startMs < durationMs }
            .distinctBy { it.startMs }
            .sortedBy { it.startMs }
        if (found.isEmpty()) return emptyList()
        val withStart = if (found.first().startMs == 0L) found else listOf(Chapter(0, null)) + found
        return withStart.take(MAX_CHAPTERS)
    }

    /** Render chapters as the file's text. Blank names become empty NAME lines. */
    fun format(chapters: List<Chapter>): String {
        val sb = StringBuilder()
        chapters.sortedBy { it.startMs }.take(MAX_CHAPTERS).forEachIndexed { i, c ->
            val n = String.format(Locale.US, "%02d", i + 1)
            sb.append("CHAPTER").append(n).append('=').append(formatTimestamp(c.startMs)).append('\n')
            sb.append("CHAPTER").append(n).append("NAME=").append(c.title?.trim().orEmpty().replace('\n', ' ').replace('\r', ' ')).append('\n')
        }
        return sb.toString()
    }

    /** `hh:mm:ss.mmm`, hours unbounded. */
    fun formatTimestamp(ms: Long): String {
        val total = ms.coerceAtLeast(0)
        val h = total / 3_600_000
        val m = (total % 3_600_000) / 60_000
        val s = (total % 60_000) / 1000
        val frac = total % 1000
        return String.format(Locale.US, "%02d:%02d:%02d.%03d", h, m, s, frac)
    }

    /** `hh:mm:ss.mmm`, `mm:ss.mmm`, `hh:mm:ss`, `mm:ss` — as mkvmerge reads them; null when it is not a time. */
    fun parseTimestamp(text: String): Long? {
        val m = TIME.matchEntire(text.trim()) ?: return null
        val parts = m.groupValues[1].split(':').map { it.toLongOrNull() ?: return null }
        if (parts.drop(1).any { it > 59 }) return null
        val seconds = when (parts.size) {
            2 -> parts[0] * 60 + parts[1]
            3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
            else -> return null
        }
        val fraction = m.groupValues[2].padEnd(3, '0').take(3).toLong()
        return seconds * 1000 + fraction
    }

    private val LINE = Regex("""CHAPTER(\d{1,3})(NAME)?=(.*)""", RegexOption.IGNORE_CASE)
    private val TIME = Regex("""(\d{1,3}(?::\d{1,2}){1,2})(?:\.(\d{1,9}))?""")
}
