package com.regolith.desktop.ui.editor

/**
 * Which chapter names to offer while one is being typed, so the same scene
 * does not end up as "The heist", "the heist" and "Heist".
 *
 * The phone draws these from its whole library's search index. The Mac has
 * no library, so it offers the names in the chapter files of the film's own
 * folder, with the phone's rules: the most-used names first, every typed word
 * must be the start of some word in the name ("the hei" finds "The heist"),
 * different capitalisations count as one name shown in its most common
 * spelling, and only the name already in the box is left out.
 */
object NameSuggestions {
    /** A safety rail, as on the phone; the chips row scrolls. */
    const val LIMIT = 50

    fun rank(names: List<String>, typed: String, limit: Int = LIMIT): List<String> {
        val words = typed.trim().lowercase().split(WORD).filter { it.isNotEmpty() }
        return names
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .groupBy { it.lowercase() }
            .values
            .map { spellings -> mostCommon(spellings) to spellings.size }
            .filter { (name, _) -> words.isEmpty() || matches(name, words) }
            .filterNot { (name, _) -> name.equals(typed.trim(), ignoreCase = true) }
            .sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first.lowercase() })
            .take(limit)
            .map { it.first }
    }

    private fun mostCommon(spellings: List<String>): String =
        spellings.groupingBy { it }.eachCount().entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .first().key

    private fun matches(name: String, typedWords: List<String>): Boolean {
        val nameWords = name.lowercase().split(WORD).filter { it.isNotEmpty() }
        return typedWords.all { t -> nameWords.any { it.startsWith(t) } }
    }

    private val WORD = Regex("""[^\p{L}\p{N}']+""")
}
