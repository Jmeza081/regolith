package com.regolith.domain.library

/**
 * How Search orders what it finds before it caps the list (40 shown).
 *
 * A video is indexed by its file name, its parsed title AND its folder path,
 * so typing a collection's name finds every video inside it as well as any
 * video named that way somewhere else. With no order, a 60-video collection
 * could push the one video actually NAMED what you typed past the cap. So:
 * an item whose OWN name holds every word comes first, then the ones found
 * only through the folders they sit in. The same for folders: one named
 * that way before one found through its parent.
 */
object SearchRank {
    /**
     * [own] (found by name), then the rest of [all], each item once, at most
     * [limit]. Each part keeps the order it came in.
     */
    fun <T, K> ownFirst(own: List<T>, all: List<T>, limit: Int, key: (T) -> K): List<T> {
        val seen = HashSet<K>()
        val out = ArrayList<T>(minOf(limit, own.size + all.size))
        for (item in own.asSequence() + all.asSequence()) {
            if (out.size == limit) break
            if (seen.add(key(item))) out += item
        }
        return out
    }
}
