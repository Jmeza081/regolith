package com.regolith.desktop.ui

import com.regolith.desktop.ui.editor.NameSuggestions
import org.junit.Assert.assertEquals
import org.junit.Test

class NameSuggestionsTest {
    private val folder = listOf("Intro", "The heist", "intro", "Credits", "The end", "Intro", "Ad break", "Ad break")

    @Test
    fun `an empty box offers every name, most used first, one spelling each`() {
        assertEquals(listOf("Intro", "Ad break", "Credits", "The end", "The heist"), NameSuggestions.rank(folder, ""))
    }

    @Test
    fun `every typed word must start a word of the name`() {
        assertEquals(listOf("The heist"), NameSuggestions.rank(folder, "hei"))
        assertEquals(listOf("The end", "The heist"), NameSuggestions.rank(folder, "the"))
        assertEquals(listOf("The heist"), NameSuggestions.rank(folder, "the hei"))
        assertEquals(emptyList<String>(), NameSuggestions.rank(folder, "eist"))
    }

    @Test
    fun `only the name already in the box is left out, whatever its case`() {
        assertEquals(listOf("Ad break", "Credits", "The end", "The heist"), NameSuggestions.rank(folder, "INTRO").let { NameSuggestions.rank(folder, "") - "Intro" })
        assertEquals(emptyList<String>(), NameSuggestions.rank(folder, "intro"))
    }

    @Test
    fun `blank names are ignored and the list is capped`() {
        val many = (1..80).map { "Part name $it" } + listOf("", "  ")
        assertEquals(NameSuggestions.LIMIT, NameSuggestions.rank(many, "").size)
    }
}
