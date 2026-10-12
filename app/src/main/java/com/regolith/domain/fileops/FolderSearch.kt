package com.regolith.domain.fileops

import java.text.Normalizer
import java.util.Locale

/**
 * Finding a folder on a share by name, for the move sheet: type a few
 * letters and every folder they could mean comes up, wherever it is in the
 * tree, best guesses first.
 *
 * Every word typed must start a word of the folder's path, and at least one
 * must start a word of its own name. So "films" finds the folders called
 * Films and not everything inside one, while "films sci" finds the Sci-fi
 * in Films and not the one in TV. Case and accents never matter ("leon"
 * finds Léon), and a name's punctuation can be left out ("scifi" finds
 * Sci-fi, "spiderman" Spider-Man). A word matches from its start, as in
 * Search: "orror" does not find Horror.
 *
 * Best first: the name exactly as typed, then names that start with it,
 * then names holding every word, then the rest. Within each, the shallower
 * folder comes first, then the path, A to Z.
 *
 * Pure Kotlin, so it is tested on the JVM. Web analogy: the filter function
 * behind a client-side search box, over a list fetched once.
 */
object FolderSearch {

    /** A folder that can be found: its own [name], and its [relPath] on the share (`Films/Sci-fi`). */
    data class Candidate(val id: Long, val name: String, val relPath: String)

    /**
     * [candidates] made ready to search. Folding every name and path is the
     * slow part, and it is the same for every letter typed, so it is done
     * once, here, and each [find] only compares words.
     */
    class Index(candidates: List<Candidate>) {
        private val entries = candidates.map { c ->
            val nameWords = words(c.name)
            Entry(c, nameWords, nameWords.joinToString(""), words(c.relPath), c.relPath.count { it == '/' })
        }

        /** The folders [query] could mean, best first; none when it has no words in it. */
        fun find(query: String): List<Candidate> {
            val typed = words(query)
            if (typed.isEmpty()) return emptyList()
            val compact = typed.joinToString("")
            return entries
                .mapNotNull { e -> e.rank(typed, compact)?.let { it to e } }
                .sortedWith(compareBy<Pair<Int, Entry>> { it.first }.thenBy { it.second.depth }.thenBy { it.second.sortKey })
                .map { it.second.candidate }
        }
    }

    private class Entry(
        val candidate: Candidate,
        val nameWords: List<String>,
        /** The name's words run together, so "scifi" can find Sci-fi. */
        val compactName: String,
        val pathWords: List<String>,
        val depth: Int,
    ) {
        val sortKey: String = candidate.relPath.lowercase(Locale.ROOT)

        /** How good a match this folder is for [typed] (lower is better), or null when it is none. */
        fun rank(typed: List<String>, compact: String): Int? {
            val startsName = compactName.startsWith(compact)
            val inName = typed.count { w -> nameWords.any { it.startsWith(w) } }
            if (!startsName) {
                // Its own name has to answer to something typed, or every
                // folder inside a match would match too.
                if (inName == 0) return null
                if (!typed.all { w -> pathWords.any { it.startsWith(w) } }) return null
            }
            return when {
                compactName == compact -> 0
                startsName -> 1
                inName == typed.size -> 2
                else -> 3
            }
        }
    }

    /**
     * Lowercase with the accents off, split on anything that is not a letter
     * or a digit. A number loses its leading zeros, so "season 1" finds
     * Season 01.
     */
    internal fun words(text: String): List<String> =
        COMBINING.replace(Normalizer.normalize(text, Normalizer.Form.NFKD), "")
            .lowercase(Locale.ROOT)
            .split(NOT_WORD)
            .filter { it.isNotEmpty() }
            .map { w -> if (w.all { it.isDigit() }) w.trimStart('0').ifEmpty { "0" } else w }

    /** NFKD leaves an accent as a mark after its letter ("é" is "e" + U+0301); these are those marks. */
    private val COMBINING = Regex("""\p{Mn}+""")
    private val NOT_WORD = Regex("""[^\p{L}\p{N}]+""")
}
