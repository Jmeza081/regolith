package com.regolith.domain.media

/**
 * Which files the Shorts feed plays: vertical, and a minute or less.
 *
 * This sits beside [MediaFileTypes] because it answers the same shape of
 * question — "does this file belong in this list?" — and, like it, decides
 * without opening anything. Pure Kotlin so the awkward half (rotation) can
 * be unit-tested against a table of real-world sizes with no database and
 * no device.
 */
object ShortsRule {

    /** "A minute or less", as the feed promises. */
    const val MAX_DURATION_MS = 60_000L

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
     * Taller than it is wide, and short.
     *
     * Anything not yet measured is NOT a Short. Null means "we have not
     * looked", and a feed that guessed would show landscape films between
     * the clips until the artwork walk caught up — the one failure worth
     * ruling out by construction. Square is out too: the test is strictly
     * less than 1, so 1:1 falls on the landscape side of the line.
     */
    fun isShort(durationMs: Long?, width: Int?, height: Int?, rotationDegrees: Int?): Boolean {
        if (durationMs == null || durationMs <= 0 || durationMs > MAX_DURATION_MS) return false
        val aspect = displayAspect(width, height, rotationDegrees) ?: return false
        return aspect < 1f
    }
}
