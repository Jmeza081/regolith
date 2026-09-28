package com.regolith.domain.playback

/**
 * Where a filmstrip's frames are taken from: the middle of each of `count`
 * equal slices, so the first frame is not the black one every film opens
 * on. Shared by the flex-mode player's strip and the Shorts panel's.
 */
object Filmstrip {
    /** One position per slice, in order; empty until the duration is known. */
    fun positions(durationMs: Long, count: Int): List<Long> =
        if (durationMs <= 0 || count <= 0) emptyList() else List(count) { i -> (durationMs * (2 * i + 1)) / (2 * count) }

    /**
     * How long one slice is: the [FrameIndex] bucket size that puts exactly
     * one of [positions] in each bucket. A film's timeline buckets by 10 s;
     * an 18-second short cut into 8 would put four frames in one bucket and
     * show the same picture four times.
     */
    fun sliceMs(durationMs: Long, count: Int): Long = (durationMs / count.coerceAtLeast(1)).coerceAtLeast(1)
}
