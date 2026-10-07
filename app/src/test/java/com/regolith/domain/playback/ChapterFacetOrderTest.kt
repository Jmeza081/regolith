package com.regolith.domain.playback

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

/**
 * [alphabetical]: Home's Moments section lists the library's moment names
 * the way a person reads a list, whatever their capitals and accents.
 */
class ChapterFacetOrderTest {

    private fun names(vararg titles: String) = titles.map { ChapterFacet(it, films = 1) }

    private fun List<ChapterFacet>.titles() = map { it.title }

    @Test
    fun `capitals do not move a name`() {
        assertEquals(
            listOf("Arrival", "bonfire", "Cake smash", "dancing"),
            names("dancing", "Cake smash", "bonfire", "Arrival").alphabetical(Locale.US).titles(),
        )
    }

    @Test
    fun `an accented letter sorts with its plain one`() {
        // SQLite's NOCASE would put both of these after every plain word.
        assertEquals(
            listOf("Dancing", "Éclair", "Fireworks", "Piñata", "Pizza", "Pool party"),
            names("Pool party", "Fireworks", "Pizza", "Éclair", "Piñata", "Dancing").alphabetical(Locale.US).titles(),
        )
    }

    @Test
    fun `how many videos use a name plays no part in the order`() {
        val sorted = listOf(ChapterFacet("Zip line", films = 40), ChapterFacet("Arrival", films = 1)).alphabetical(Locale.US)
        assertEquals(listOf("Arrival", "Zip line"), sorted.titles())
    }

    @Test
    fun `names that read alike keep one fixed order between them`() {
        // Equal to the collator; the raw string breaks the tie, so a refresh
        // that delivers them the other way round never swaps two chips.
        assertEquals(
            names("Éclair", "Eclair").alphabetical(Locale.US).titles(),
            names("Eclair", "Éclair").alphabetical(Locale.US).titles(),
        )
    }
}
