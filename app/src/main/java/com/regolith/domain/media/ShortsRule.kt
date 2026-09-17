package com.regolith.domain.media

/**
 * How long a clip may be and still count as a Short.
 *
 * Stored by [name] so the order of this enum can change without moving
 * anyone's setting — the same contract [com.regolith.domain.security.LockAfter]
 * keeps. The label is what the segmented control shows.
 */
enum class ShortsLength(val label: String, val maxMs: Long) {
    THIRTY("30s", 30_000L),
    SIXTY("60s", 60_000L),
    NINETY("90s", 90_000L),
    ;

    companion object {
        /** A minute is what the feed promised before this was a choice. */
        val DEFAULT = SIXTY

        /** Reads a stored name back; anything unknown falls back to [DEFAULT]. */
        fun of(name: String?): ShortsLength = entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}

/**
 * Which files the Shorts feed plays: vertical, and no longer than the
 * length the owner picked.
 *
 * This sits beside [MediaFileTypes] because it answers the same shape of
 * question — "does this file belong in this list?" — and, like it, decides
 * without opening anything. Pure Kotlin so the awkward half (rotation) can
 * be unit-tested against a table of real-world sizes with no database and
 * no device.
 */
object ShortsRule {

    /**
     * Width divided by height AS DISPLAYED, with the container's rotation
     * applied. Null when the file has not been measured yet.
     *
     * The stored size is not the displayed size. A clip filmed on a phone
     * is routinely muxed as 1920x1080 with a quarter turn recorded beside
     * it, and the player honours that turn — so comparing the stored width
     * and height would call an upright video landscape.
     */
    fun displayAspect(width: Int?, height: Int?, rotationDegrees: Int?): Float? {
        if (width == null || height == null || width <= 0 || height <= 0) return null
        // 90 and 270 swap the axes; 0 and 180 do not. The double modulo
        // keeps a negative rotation (some muxers write -90) honest.
        val quarterTurned = rotationDegrees != null && (((rotationDegrees % 180) + 180) % 180) == 90
        val shownWidth = if (quarterTurned) height else width
        val shownHeight = if (quarterTurned) width else height
        return shownWidth.toFloat() / shownHeight.toFloat()
    }

    /**
     * Taller than it is wide, and no longer than [maxDurationMs].
     *
     * The limit is a parameter rather than a constant because it is now a
     * setting: the same file is a Short at 90s and not at 30s, so nothing
     * downstream may cache the answer.
     *
     * Anything not yet measured is NOT a Short. Null means "we have not
     * looked", and a feed that guessed would show landscape films between
     * the clips until the artwork walk caught up — the one failure worth
     * ruling out by construction. Square is out too: the test is strictly
     * less than 1, so 1:1 falls on the landscape side of the line.
     */
    fun isShort(durationMs: Long?, width: Int?, height: Int?, rotationDegrees: Int?, maxDurationMs: Long): Boolean {
        if (durationMs == null || durationMs <= 0 || durationMs > maxDurationMs) return false
        val aspect = displayAspect(width, height, rotationDegrees) ?: return false
        return aspect < 1f
    }
}
