package com.regolith.domain.playback

/**
 * Which of a small, fixed set of players holds which clip of a feed.
 *
 * A vertical feed wants the next clip already buffering while you watch the
 * current one, which means more than one player alive at once. It must NOT
 * mean one player per clip: a decoder is a scarce, OS-owned thing, and
 * Media3 fails outright rather than degrading when they run out.
 *
 * So the feed keeps a ring of [size] players and walks it. The arithmetic
 * is the whole trick, and it is why this is a class and not a comment: any
 * [size] consecutive positions land on [size] DIFFERENT slots, so the
 * player that falls out of the window behind you is exactly the one the
 * clip ahead of you needs. Nothing is allocated or released while swiping.
 *
 * Pure Kotlin, no Android imports (G9) — the same reason [FrameIndex] is.
 * The part that is easy to get wrong is tested on the JVM; what is left in
 * the pool itself is little more than calls to ExoPlayer.
 */
class ShortsRing(val size: Int = DEFAULT_SIZE) {

    init {
        require(size >= 2) { "a ring of $size cannot hold a clip and its neighbour" }
    }

    /**
     * The player a feed position uses. Stable, so the same clip always
     * returns to the same player, and negative positions are handled
     * because a window near the top of the feed reaches past it.
     */
    fun slotFor(index: Int): Int = ((index % size) + size) % size

    /**
     * The feed positions kept warm around [index], clipped to a feed of
     * [count]. Centred as far as the ring allows, favouring what is ahead:
     * a ring of three keeps one behind and one in front, because swiping
     * back is rarer than swiping on but not rare enough to pay a reopen.
     */
    fun window(index: Int, count: Int): List<Int> {
        if (count <= 0 || index < 0) return emptyList()
        val behind = (size - 1) / 2
        val first = index - behind
        return (first until first + size).filter { it in 0 until count }
    }

    /** Positions that fall out of the window on a move from [from] to [to]. */
    fun released(from: Int, to: Int, count: Int): List<Int> {
        val kept = window(to, count).toSet()
        return window(from, count).filter { it !in kept }
    }

    /** Positions that need preparing on a move from [from] to [to]. */
    fun added(from: Int, to: Int, count: Int): List<Int> {
        val had = window(from, count).toSet()
        return window(to, count).filter { it !in had }
    }

    companion object {
        /** Previous, current, next. Three decoders, and swiping either way is instant. */
        const val DEFAULT_SIZE = 3
    }
}
