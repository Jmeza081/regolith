package com.regolith.domain.playback

import kotlin.random.Random

/** How long each picture stays in a story (Settings › Playback › Picture stories). */
enum class StoryPace(val label: String, val ms: Long) {
    THREE("3 s", 3_000L),
    FIVE("5 s", 5_000L),
    EIGHT("8 s", 8_000L),
    ;

    companion object {
        /** Instagram's, and the owner's lean on the Images canvas (Q15). */
        val DEFAULT = FIVE

        /** Reads a stored name back; anything unknown falls back to [DEFAULT]. */
        fun of(name: String?): StoryPace = entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}

/**
 * A collection's pictures played one after another, the way a phone plays
 * stories (the canvas "Images on the share", Story-Play and Story-Inner):
 * a short bar per picture across the top, each filling while its picture
 * shows. Pure, so the order and the bar are tested without a screen.
 */
object Story {

    /** The most segments the bar shows: past this a segment is too thin to read (Q21). */
    const val MAX_SEGMENTS = 20

    /**
     * Which segments the bar shows while picture [index] of [count] is up:
     * all of them up to [max]; past that, the [max] around the one showing,
     * sliding along with the story, and "124 of 300" says where it is.
     */
    fun window(count: Int, index: Int, max: Int = MAX_SEGMENTS): IntRange {
        if (count <= max) return 0 until count
        val start = (index - max / 2).coerceIn(0, count - max)
        return start until start + max
    }

    /**
     * How long a picture stays: the pace, or a moving GIF's own length when
     * that is longer, so it plays through once (Q16).
     */
    fun durationMs(pace: StoryPace, gifMs: Long?): Long = maxOf(pace.ms, gifMs ?: 0L)

    /** The whole story's length before anything plays, for "Play pictures · 7 min" (GIFs counted at the pace). */
    fun lengthMs(count: Int, pace: StoryPace): Long = count * pace.ms

    /**
     * [ids] in the order a story plays them: as they are, or shuffled by
     * [seed]. A seed rather than a fresh shuffle each time, so a story the
     * app brings back after being put away plays the same order it started.
     */
    fun order(ids: List<Long>, seed: Long?): List<Long> = if (seed == null) ids else ids.shuffled(Random(seed))

    /** Where a story played from [startId] begins in [order]: its place, or the start when it is not there. */
    fun startIndex(order: List<Long>, startId: Long?): Int = startId?.let { order.indexOf(it) }?.takeIf { it >= 0 } ?: 0
}
