package com.regolith.domain.playback

/**
 * One chapter marker in a file: where it starts, and what it is called.
 *
 * Two things produce these. A container may carry real ones, named by
 * whoever made the file — Regolith reads those and never writes them. Every
 * other file gets [ChapterMarks.evenly]: the runtime cut into equal pieces,
 * so that jumping through a film is always one tap away and never depends
 * on how the file happened to be muxed.
 */
data class Chapter(
    val startMs: Long,
    /** Null when the marker has no name — always the case for even divisions. */
    val title: String?,
) {
    /** What the sheet shows: the name, or "Part n" when there isn't one. */
    fun label(index: Int): String = title?.takeIf { it.isNotBlank() } ?: "Part ${index + 1}"
}

/**
 * Even divisions of a runtime, for the files that carry no chapters of
 * their own — which is nearly all of them.
 *
 * The interval is picked off a ladder of round numbers rather than by
 * dividing the runtime, because "every 10 minutes" is a thing you can hold
 * in your head and "every 11 minutes 24 seconds" is not. The ladder is
 * walked until the count fits, so a 40-minute episode gets 5-minute parts
 * and a three-hour film gets 15-minute ones.
 *
 * Pure, so the choice is testable without a file.
 */
object ChapterMarks {
    /** Round intervals, smallest first. */
    val intervalsMs = listOf(10_000L, 15_000L, 30_000L, 60_000L, 120_000L, 300_000L, 600_000L, 900_000L)

    /** More than this many rows stops being a way to get somewhere and becomes a list to read. */
    const val MAX_PARTS = 12

    /** Below this there is nothing to divide: the scrubber is already the whole film. */
    const val MIN_DURATION_MS = 20_000L

    /** The interval a runtime is cut at, or null when it is too short to cut. */
    fun intervalFor(durationMs: Long): Long? {
        if (durationMs < MIN_DURATION_MS) return null
        intervalsMs.firstOrNull { parts(durationMs, it) <= MAX_PARTS }?.let { return it }
        // Longer than the ladder reaches: fall back to even parts, rounded up
        // to the minute so the numbers still read as round.
        val raw = durationMs / MAX_PARTS
        return (raw + 59_999) / 60_000 * 60_000
    }

    /** The markers themselves. Empty for a file too short to divide. */
    fun evenly(durationMs: Long): List<Chapter> {
        val interval = intervalFor(durationMs) ?: return emptyList()
        val count = parts(durationMs, interval)
        return List(count) { Chapter(startMs = it * interval, title = null) }
    }

    /**
     * How many parts an interval makes. A final part shorter than a quarter
     * of the interval is absorbed into the one before it, so a 61-minute film
     * does not end with a one-minute "Part 13".
     */
    private fun parts(durationMs: Long, intervalMs: Long): Int {
        val whole = (durationMs / intervalMs).toInt()
        val remainder = durationMs % intervalMs
        return (if (remainder > intervalMs / 4) whole + 1 else whole).coerceAtLeast(1)
    }
}
