package com.regolith.domain.playback

/**
 * The scrub-preview cache's bookkeeping, without the bitmaps. Positions are
 * quantised into buckets of [intervalMs] (Jellyfin uses 10 s), so dragging
 * across a two-hour film asks for at most 720 distinct frames and a small
 * drag reuses the frame it already has.
 *
 * [nearest] returns the closest loaded bucket within [toleranceBuckets], so
 * while the exact frame is still loading the preview shows its neighbour
 * rather than nothing. Least-recently-used entries go first when [capacity]
 * is reached.
 *
 * Generic over the frame type so it is unit-tested on the JVM with strings.
 */
class FrameIndex<T : Any>(
    val intervalMs: Long = DEFAULT_INTERVAL_MS,
    private val capacity: Int = DEFAULT_CAPACITY,
    private val toleranceBuckets: Int = 2,
    /** Called when an entry is evicted, e.g. to recycle a bitmap. */
    private val onEvict: (T) -> Unit = {},
) {
    private val entries = object : LinkedHashMap<Long, T>(16, 0.75f, /* accessOrder = */ true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, T>): Boolean {
            val evict = size > capacity
            if (evict) onEvict(eldest.value)
            return evict
        }
    }

    val size: Int get() = entries.size

    fun bucketOf(positionMs: Long): Long = positionMs.coerceAtLeast(0) / intervalMs

    /** The position a bucket's frame represents: the middle of the bucket. */
    fun positionOf(bucket: Long): Long = bucket * intervalMs + intervalMs / 2

    fun contains(bucket: Long): Boolean = entries.containsKey(bucket)

    fun put(bucket: Long, frame: T) {
        entries[bucket] = frame
    }

    /** The frame for [positionMs]'s bucket, else the nearest loaded neighbour within tolerance. */
    fun nearest(positionMs: Long): T? {
        val want = bucketOf(positionMs)
        entries[want]?.let { return it }
        for (d in 1..toleranceBuckets) {
            entries[want - d]?.let { return it }
            entries[want + d]?.let { return it }
        }
        return null
    }

    /** Buckets still worth loading around [positionMs], nearest first. */
    fun missingAround(positionMs: Long, radius: Int, maxBucket: Long): List<Long> {
        val centre = bucketOf(positionMs)
        val out = mutableListOf<Long>()
        if (!contains(centre) && centre <= maxBucket) out += centre
        for (d in 1..radius) {
            val before = centre - d
            val after = centre + d
            if (before >= 0 && !contains(before)) out += before
            if (after <= maxBucket && !contains(after)) out += after
        }
        return out
    }

    fun clear() {
        entries.values.forEach(onEvict)
        entries.clear()
    }

    companion object {
        const val DEFAULT_INTERVAL_MS = 10_000L
        /** 40 frames at 320×180 ARGB is ~9 MB: the design's "extra data from the share", bounded. */
        const val DEFAULT_CAPACITY = 40
    }
}
