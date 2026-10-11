package com.regolith.domain.fileops

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The move sheet's folder search: what a few typed letters find, and in what order. */
class FolderSearchTest {

    private val tree = listOf(
        "Films",
        "Films/Sci-fi",
        "Films/Sci-fi/Arrival (2016)",
        "Films/Léon (1994)",
        "Films/Spider-Man",
        "TV",
        "TV/Sci-fi",
        "TV/Severance/Season 01",
        "TV/Severance",
        "Home videos/Films we made",
        "Archive/Old films",
        "Archive/Old films/Sci-fi",
        "Horror",
    )
    private val index = FolderSearch.Index(
        tree.mapIndexed { i, path -> FolderSearch.Candidate(id = i.toLong(), name = path.substringAfterLast('/'), relPath = path) },
    )

    private fun find(query: String) = index.find(query).map { it.relPath }

    @Test
    fun `a name finds the folders called that, not everything inside them`() {
        val found = find("films")
        assertTrue(found.containsAll(listOf("Films", "Home videos/Films we made", "Archive/Old films")))
        assertTrue("inside Films, not called it", "Films/Sci-fi" !in found)
    }

    @Test
    fun `more words narrow by where the folder is`() {
        assertEquals(listOf("Films/Sci-fi", "Archive/Old films/Sci-fi"), find("films sci"))
        assertEquals(listOf("TV/Sci-fi"), find("tv sci"))
    }

    @Test
    fun `case, accents and a name's punctuation do not matter`() {
        assertEquals(listOf("Films/Léon (1994)"), find("LEON"))
        assertEquals(listOf("Films/Léon (1994)"), find("léon"))
        assertEquals(listOf("Films/Spider-Man"), find("spiderman"))
        assertEquals(listOf("Films/Spider-Man"), find("spider man"))
    }

    @Test
    fun `a word matches from its start, not from its middle`() {
        assertEquals(emptyList<String>(), find("orror"))
        assertEquals(listOf("Horror"), find("hor"))
    }

    @Test
    fun `best first - exact, starts with, every word in the name, then by place`() {
        // "Films" is exactly it; "Films we made" starts with it; "Old films"
        // only holds it. Shallower first, then the path A to Z.
        assertEquals(listOf("Films", "Home videos/Films we made", "Archive/Old films"), find("films"))
        // All called exactly that: the shallower first, then A to Z.
        assertEquals(listOf("Films/Sci-fi", "TV/Sci-fi", "Archive/Old films/Sci-fi"), find("scifi"))
        // Only its own name answers to a word: what is inside Severance is not Severance.
        assertEquals(listOf("TV/Severance"), find("severance"))
    }

    @Test
    fun `a year in brackets is a word like any other`() {
        assertEquals(listOf("Films/Sci-fi/Arrival (2016)"), find("2016"))
        assertEquals(listOf("Films/Sci-fi/Arrival (2016)"), find("arrival 2016"))
    }

    @Test
    fun `a number's leading zeros do not matter`() {
        assertEquals(listOf("TV/Severance/Season 01"), find("season 1"))
        assertEquals(listOf("TV/Severance/Season 01"), find("season 01"))
    }

    @Test
    fun `nothing typed but spaces or punctuation finds nothing`() {
        assertEquals(emptyList<String>(), find(""))
        assertEquals(emptyList<String>(), find("  -/ "))
    }
}
