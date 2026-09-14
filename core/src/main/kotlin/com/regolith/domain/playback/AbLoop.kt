package com.regolith.domain.playback

/**
 * An A–B repeat span (design section 10). Points are nudged in half
 * seconds rather than typed: the reason anyone loops is to catch a phrase
 * they keep missing. Repeats until cleared; there is no count.
 */
data class AbLoop(val aMs: Long, val bMs: Long) {
    init {
        require(bMs > aMs) { "B must come after A" }
    }

    val lengthMs: Long get() = bMs - aMs

    /** True when playback has run past B and should jump back to A. */
    fun shouldRestart(positionMs: Long): Boolean = positionMs >= bMs

    /** Move A by [deltaMs], never past B minus [MIN_SPAN_MS] nor before 0. */
    fun nudgeA(deltaMs: Long): AbLoop = copy(aMs = (aMs + deltaMs).coerceIn(0, bMs - MIN_SPAN_MS))

    /** Move B by [deltaMs], never before A plus [MIN_SPAN_MS] nor past [durationMs]. */
    fun nudgeB(deltaMs: Long, durationMs: Long): AbLoop =
        copy(bMs = (bMs + deltaMs).coerceIn(aMs + MIN_SPAN_MS, durationMs.coerceAtLeast(aMs + MIN_SPAN_MS)))

    companion object {
        const val NUDGE_MS = 500L
        const val MIN_SPAN_MS = 500L

        /**
         * Build a loop from two taps. Order does not matter, and two taps
         * closer than the minimum span yield a minimum-length loop.
         */
        fun between(firstMs: Long, secondMs: Long, durationMs: Long): AbLoop {
            val a = minOf(firstMs, secondMs).coerceAtLeast(0)
            val b = maxOf(firstMs, secondMs).coerceAtLeast(a + MIN_SPAN_MS).coerceAtMost(durationMs.coerceAtLeast(a + MIN_SPAN_MS))
            return AbLoop(a, b)
        }
    }
}
