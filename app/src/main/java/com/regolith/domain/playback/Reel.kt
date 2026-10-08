package com.regolith.domain.playback

/*
 * The Moments reel: a collection's named moments played one after another, a
 * few seconds of each, from its Moments tab ("Play moments"). The canvas
 * "Next Five Features", boards Reel-Tab, Reel-Play and Reel-Inner.
 */

/** A moment to make a clip of: the video, where the mark is, its name and the video's. */
data class ReelMoment(val fileId: Long, val startMs: Long, val name: String, val videoName: String)

/** One moment as the reel plays it: [fileId] from [startMs] to [endMs]. */
data class ReelClip(
    val fileId: Long,
    val startMs: Long,
    val endMs: Long,
    /** The moment's name. */
    val name: String,
    /** The video it is in, as its tile names it. */
    val videoName: String,
) {
    val durationMs: Long get() = endMs - startMs
}

object Reel {
    /**
     * How long each clip runs: ten seconds keeps a reel's length something
     * you can say before pressing play (six moments, about a minute).
     */
    const val CLIP_MS = 10_000L

    /** The shortest a clip gets, so two marks a moment apart do not flash past. */
    const val MIN_CLIP_MS = 1_000L

    /**
     * [moments] as clips, in the order given. Each runs [CLIP_MS], less if the
     * next mark in the same video comes sooner or the video ends first
     * ([durations] by file; one not known yet sets no limit). A moment at or
     * past its video's end has nothing to play and is left out.
     */
    fun clips(moments: List<ReelMoment>, durations: Map<Long, Long?>): List<ReelClip> {
        val marks = moments.groupBy { it.fileId }.mapValues { (_, inFile) -> inFile.map { it.startMs }.distinct().sorted() }
        return moments.mapNotNull { moment ->
            val duration = durations[moment.fileId]?.takeIf { it > 0 }
            if (duration != null && moment.startMs >= duration) return@mapNotNull null
            val nextMark = marks[moment.fileId]?.firstOrNull { it > moment.startMs }
            var end = moment.startMs + CLIP_MS
            if (nextMark != null) end = minOf(end, maxOf(nextMark, moment.startMs + MIN_CLIP_MS))
            if (duration != null) end = minOf(end, duration)
            ReelClip(moment.fileId, moment.startMs, end, moment.name, moment.videoName)
        }
    }

    /** The reel's length: every clip added up. */
    fun lengthMs(clips: List<ReelClip>): Long = clips.sumOf { it.durationMs }

    /**
     * The length as a person would say it before pressing play: seconds under
     * a minute, else whole minutes ("45 s", "1 min", "3 min").
     */
    fun roughLength(ms: Long): String =
        if (ms < 60_000L) "${(ms / 1000L).coerceAtLeast(1L)} s" else "${(ms + 30_000L) / 60_000L} min"
}
