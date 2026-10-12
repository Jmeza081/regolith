package com.regolith.domain.playback

/**
 * Where each chapter's picture on the chapter sheet is taken from, as a time
 * in the film. The frame at that time is cached under it
 * ([com.regolith.domain.artwork.ArtworkOwner.Moment]), so everything that asks
 * for the same time shares one picture.
 *
 * Two rules, because a chapter is one of two things:
 *  - a MOMENT, a mark the user named, shows the frame at its own time. That is
 *    the picture Search, Home, a collection's Moments and the video's page
 *    already show for it, so it looks the same everywhere, and it is usually
 *    on disk before the sheet asks (the artwork walk grabs named marks).
 *  - any other chapter (the file's own markers, the even split, a mark with no
 *    name) shows a frame a quarter of the way INTO its part. A boundary is
 *    usually a cut, and the frame on a cut is often black or a title card.
 *
 * The second rule rounds down to a whole second, so a runtime that two readers
 * measure a few milliseconds apart (the player, and the file's row) still names
 * the same frame.
 *
 * Pure and shared: the player asks for these frames, and the code that
 * rewrites a film's marks keeps them
 * ([com.regolith.domain.artwork.MomentFrames.pruneMoments]).
 */
object ChapterStills {
    /** How far into its part a chapter's picture is taken from: past the cut at its start. */
    const val INTO_PART = 0.25

    /** One time per chapter, in the order of [chapters], which is by start like every chapter list. */
    fun timesFor(chapters: List<Chapter>, durationMs: Long, source: ChapterSource): List<Long> =
        chapters.indices.map { timeFor(chapters, it, durationMs, source) }

    private fun timeFor(chapters: List<Chapter>, index: Int, durationMs: Long, source: ChapterSource): Long {
        val chapter = chapters[index]
        if (source == ChapterSource.USER && !chapter.title.isNullOrBlank()) return chapter.startMs
        // The last part runs to the end of the film. With the runtime unknown
        // it has no length, and its picture is its first frame.
        val end = chapters.getOrNull(index + 1)?.startMs ?: durationMs
        val into = ((end - chapter.startMs).coerceAtLeast(0L) * INTO_PART).toLong()
        return ((chapter.startMs + into) / 1_000L * 1_000L).coerceAtLeast(chapter.startMs)
    }
}
