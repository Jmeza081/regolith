package com.regolith.domain.playback

/**
 * A set of chapters being edited, with the rules that keep it a set worth
 * saving. Immutable: every verb returns a new draft, so the ViewModel can
 * hold it in one `StateFlow` and the editor never sees a half-applied
 * change. Pure Kotlin, so every rule below is unit-tested without a
 * player.
 *
 * The rules:
 *  - [marks] are sorted by start, and the first is always at 0 ms — a film
 *    starts in a chapter. It can be renamed, never moved or removed.
 *  - Marks stay at least [MIN_GAP_MS] apart and the same clear of the end;
 *    a mark asked for inside that gap selects the mark already there.
 *  - A name is kept exactly as typed while editing (trimming per keystroke
 *    would eat the space you just typed); [chapters] hands out the trimmed
 *    version, with a blank name as null so it shows as "Part n".
 *
 * @property fileId the film these belong to, captured when editing began
 *   so autoplay moving on cannot make Done write to the wrong file.
 */
data class ChapterDraft(
    val fileId: Long,
    val durationMs: Long,
    val marks: List<Chapter>,
    /** True once anything differs from what editing began with. */
    val dirty: Boolean = false,
    /** The mark whose row is open, or null. */
    val selected: Int? = null,
) {
    /** What gets saved: the marks with names trimmed and blanks dropped to null. */
    val chapters: List<Chapter>
        get() = marks.map { it.copy(title = it.title?.trim()?.takeIf { t -> t.isNotEmpty() }) }

    /** The flags the scrubber draws. */
    val marksMs: List<Long> get() = marks.map { it.startMs }

    /** Add a mark at [ms], or select the one already within [MIN_GAP_MS] of it — at 0:00 that is the start mark. */
    fun mark(ms: Long): ChapterDraft {
        val at = ms.coerceIn(0L, (durationMs - MIN_GAP_MS).coerceAtLeast(0L))
        marks.indexOfFirst { kotlin.math.abs(it.startMs - at) < MIN_GAP_MS }.takeIf { it >= 0 }?.let { return copy(selected = it) }
        if (marks.size >= MAX_MARKS) return this
        val next = (marks + Chapter(at, null)).sortedBy { it.startMs }
        return copy(marks = next, dirty = true, selected = next.indexOfFirst { it.startMs == at })
    }

    /**
     * Move a mark, kept between its neighbours so its index — and the
     * selection — stays put while a flag is dragged. The first mark does
     * not move.
     */
    fun move(index: Int, ms: Long): ChapterDraft {
        if (index <= 0 || index >= marks.size) return this
        val lo = marks[index - 1].startMs + MIN_GAP_MS
        val hi = (marks.getOrNull(index + 1)?.startMs ?: durationMs) - MIN_GAP_MS
        if (lo > hi) return this
        val at = ms.coerceIn(lo, hi)
        if (at == marks[index].startMs) return this
        return copy(marks = marks.toMutableList().also { it[index] = it[index].copy(startMs = at) }, dirty = true)
    }

    fun nudge(index: Int, deltaMs: Long): ChapterDraft = marks.getOrNull(index)?.let { move(index, it.startMs + deltaMs) } ?: this

    /** Rename as typed; the trimming happens in [chapters]. Capped at [NAME_MAX] characters. */
    fun rename(index: Int, title: String): ChapterDraft {
        val current = marks.getOrNull(index) ?: return this
        val next = title.take(NAME_MAX)
        if (next == (current.title ?: "")) return this
        return copy(marks = marks.toMutableList().also { it[index] = current.copy(title = next) }, dirty = true)
    }

    /** Drop a mark. The first cannot go; the selection follows the rows down. */
    fun remove(index: Int): ChapterDraft {
        if (index <= 0 || index >= marks.size) return this
        val nextSelected = when {
            selected == null -> null
            selected == index -> null
            selected > index -> selected - 1
            else -> selected
        }
        return copy(marks = marks.filterIndexed { i, _ -> i != index }, dirty = true, selected = nextSelected)
    }

    fun select(index: Int?): ChapterDraft = copy(selected = index?.takeIf { it in marks.indices })

    /** Start fresh: only the start mark stays, unnamed. Cancel still undoes it. */
    fun clearAll(): ChapterDraft =
        if (marks.size == 1 && marks[0].title == null) this
        else copy(marks = listOf(Chapter(0, null)), dirty = true, selected = null)

    /**
     * Where mark [index] may sit: a second clear of its neighbours and of
     * the end. Null for the start mark, which does not move. What the
     * editor's Start field checks a typed time against, so the message can
     * name the nearest allowed times.
     */
    fun bounds(index: Int): LongRange? {
        if (index <= 0 || index >= marks.size) return null
        val lo = marks[index - 1].startMs + MIN_GAP_MS
        val hi = (marks.getOrNull(index + 1)?.startMs ?: durationMs) - MIN_GAP_MS
        return if (lo > hi) null else lo..hi
    }

    companion object {
        /** Two marks closer than this are one place, not two. */
        const val MIN_GAP_MS = 1_000L

        /** Past this the sheet is a list to read, not a way to get somewhere — well past, so it never binds in practice. */
        const val MAX_MARKS = 200

        const val NAME_MAX = 80

        /** Half a second and five seconds: the two nudge sizes the editor offers. */
        const val NUDGE_FINE_MS = 500L
        const val NUDGE_COARSE_MS = 5_000L

        /**
         * A typed clock — `12:30`, `0:12:30`, `1:02:15.5`, or bare seconds —
         * as milliseconds, or null when it is not one. Tenths are the finest
         * it reads: the scrubber and the nudges do not go finer either.
         */
        fun parseClock(text: String): Long? {
            val t = text.trim()
            if (!Regex("""\d{1,3}(:\d{1,2}){0,2}(\.\d)?""").matches(t)) return null
            val whole = t.substringBefore('.')
            val tenth = t.substringAfter('.', "").toIntOrNull() ?: 0
            val parts = whole.split(':').map { it.toLong() }
            if (parts.drop(1).any { it > 59 }) return null
            val seconds = when (parts.size) {
                1 -> parts[0]
                2 -> parts[0] * 60 + parts[1]
                else -> parts[0] * 3600 + parts[1] * 60 + parts[2]
            }
            return seconds * 1000 + tenth * 100L
        }

        /**
         * Start editing from [chapters] — the even split, the file's own, or
         * a previous edit — so there is always something to rename rather
         * than a blank to fill. A missing 0 ms mark is added unnamed; marks
         * past the end are dropped.
         */
        fun seed(fileId: Long, chapters: List<Chapter>, durationMs: Long): ChapterDraft {
            val kept = chapters.filter { it.startMs in 0 until durationMs }.sortedBy { it.startMs }
            val withStart = if (kept.firstOrNull()?.startMs == 0L) kept else listOf(Chapter(0, null)) + kept
            return ChapterDraft(fileId = fileId, durationMs = durationMs, marks = withStart)
        }
    }
}
