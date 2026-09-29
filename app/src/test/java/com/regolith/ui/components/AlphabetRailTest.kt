package com.regolith.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The rail's arithmetic: which letter a name files under, where each letter
 * starts, and which letter a finger is on. The gesture itself needs a
 * device; what it lands on does not.
 */
class AlphabetRailTest {

    @Test
    fun `a name files under its first letter, whatever its case or accent`() {
        assertEquals('A', AlphabetIndex.letterOf("alien"))
        assertEquals('A', AlphabetIndex.letterOf("Aliens"))
        assertEquals('E', AlphabetIndex.letterOf("Élan"))
        assertEquals('O', AlphabetIndex.letterOf("Ōkami"))
        // Leading spaces are not a letter; the name after them is.
        assertEquals('S', AlphabetIndex.letterOf("  Season 02"))
    }

    @Test
    fun `anything that is not A to Z files under the hash`() {
        assertEquals('#', AlphabetIndex.letterOf("2001 A Space Odyssey"))
        assertEquals('#', AlphabetIndex.letterOf("[Extras]"))
        assertEquals('#', AlphabetIndex.letterOf("_unsorted"))
        assertEquals('#', AlphabetIndex.letterOf("東京物語"))
        assertEquals('#', AlphabetIndex.letterOf(""))
    }

    @Test
    fun `each letter jumps to the FIRST name under it`() {
        val index = AlphabetIndex(listOf("2012", "Alien", "Aliens", "Brazil", "Dune", "Dune Part Two"))
        assertEquals(0, index.positionOf('#'))
        assertEquals(1, index.positionOf('A'))
        assertEquals(3, index.positionOf('B'))
        assertEquals(4, index.positionOf('D'))
        assertEquals(setOf('#', 'A', 'B', 'D'), index.present)
    }

    @Test
    fun `a letter with nothing under it goes nowhere`() {
        val index = AlphabetIndex(listOf("Alien", "Brazil"))
        // Not "the next letter along": a dimmed letter is a disabled one.
        assertNull(index.positionOf('C'))
        assertNull(index.positionOf('#'))
    }

    @Test
    fun `the rail has every letter, hash first`() {
        assertEquals(27, AlphabetIndex.LETTERS.size)
        assertEquals('#', AlphabetIndex.LETTERS.first())
        assertEquals('Z', AlphabetIndex.LETTERS.last())
    }

    @Test
    fun `a finger maps to the place it is over`() {
        // 27 places of 10px, filling a 270px strip exactly.
        assertEquals(0, railLetterAt(5f, 270f, maxSlot = 18f))
        assertEquals(1, railLetterAt(15f, 270f, maxSlot = 18f))
        assertEquals(26, railLetterAt(265f, 270f, maxSlot = 18f))
    }

    @Test
    fun `on a tall strip the places stay together, centred`() {
        // 27 × 18px = 486px, centred in 1000px: the letters start at 257px.
        assertEquals(0, railLetterAt(260f, 1000f, maxSlot = 18f))
        assertEquals(1, railLetterAt(257f + 18f + 1f, 1000f, maxSlot = 18f))
        // Above the first place or below the last is still the first or last.
        assertEquals(0, railLetterAt(10f, 1000f, maxSlot = 18f))
        assertEquals(26, railLetterAt(990f, 1000f, maxSlot = 18f))
    }

    @Test
    fun `labels thin out once they would touch`() {
        assertEquals(1, railLabelEvery(slot = 18f, lineHeight = 13f))
        assertEquals(1, railLabelEvery(slot = 13f, lineHeight = 13f))
        assertEquals(2, railLabelEvery(slot = 8f, lineHeight = 13f))
        assertEquals(3, railLabelEvery(slot = 5f, lineHeight = 13f))
    }
}
