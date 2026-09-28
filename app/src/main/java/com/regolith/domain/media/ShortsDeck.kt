package com.regolith.domain.media

import kotlin.random.Random

/**
 * The order the Shorts feed plays in.
 *
 * Pure, and tested on the JVM (G9), because the rule that matters is easy to
 * break without noticing: clips taken out of the deck are taken out AFTER
 * it is shuffled. Shuffling the list with them already gone would shuffle a
 * different list — the same seed over eleven clips deals a completely new
 * order from the one it dealt over twelve — and every clip after the one
 * you are watching would change places the moment you skipped one.
 */
object ShortsDeck {
    /**
     * [clips] in playing order: shuffled by [seed] (the same seed always
     * deals the same order), or as given when [seed] is null; then without
     * the ones whose [idOf] is in [skipped].
     */
    fun <T> deal(clips: List<T>, seed: Long?, skipped: Set<Long>, idOf: (T) -> Long): List<T> {
        val dealt = if (seed == null) clips else clips.shuffled(Random(seed))
        return if (skipped.isEmpty()) dealt else dealt.filterNot { idOf(it) in skipped }
    }
}
