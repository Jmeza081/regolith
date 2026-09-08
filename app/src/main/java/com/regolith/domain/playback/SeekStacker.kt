package com.regolith.domain.playback

/**
 * Double-tap seeking that stacks (design section 10, gesture map):
 * a double-tap seeks 10 s, and every further tap inside the stacking
 * window adds another 10 s in the same direction ("20s, 30s").
 *
 * Pure state machine: feed it taps with timestamps, read [pendingLabel].
 */
class SeekStacker(
    private val stepMs: Long = STEP_MS,
    private val windowMs: Long = WINDOW_MS,
) {
    /** +1 forward, -1 back, 0 idle. */
    var direction: Int = 0
        private set

    /** Accumulated seek in the current stack, signed. */
    var accumulatedMs: Long = 0
        private set

    private var lastTapAtMs: Long = Long.MIN_VALUE

    /** A double-tap on the left (-1) or right (+1) half. Returns the seek to apply now. */
    fun onDoubleTap(dir: Int, nowMs: Long): Long {
        if (dir != direction || nowMs - lastTapAtMs > windowMs) {
            direction = dir
            accumulatedMs = 0
        }
        return stack(dir, nowMs)
    }

    /**
     * A single tap after a double-tap keeps stacking while the window is
     * open. Returns the seek to apply, or null if this tap is not part of
     * a stack (so the caller treats it as "toggle controls").
     */
    fun onSingleTap(dir: Int, nowMs: Long): Long? {
        if (direction == 0 || dir != direction || nowMs - lastTapAtMs > windowMs) return null
        return stack(dir, nowMs)
    }

    /** "+20s" / "−30s" while a stack is live, else null. */
    fun pendingLabel(nowMs: Long): String? {
        if (direction == 0 || nowMs - lastTapAtMs > windowMs) return null
        val secs = kotlin.math.abs(accumulatedMs) / 1000
        return if (direction > 0) "+${secs}s" else "−${secs}s"
    }

    fun reset() {
        direction = 0
        accumulatedMs = 0
        lastTapAtMs = Long.MIN_VALUE
    }

    private fun stack(dir: Int, nowMs: Long): Long {
        lastTapAtMs = nowMs
        accumulatedMs += dir * stepMs
        return dir * stepMs
    }

    companion object {
        const val STEP_MS = 10_000L
        const val WINDOW_MS = 700L
    }
}
