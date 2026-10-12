package com.regolith.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [pathStepKey]: a step of a video page's Path line opens its folder in the
 * tab the page was opened from, Browse's from Browse and the Library's from
 * anywhere else; a phone video's album opens on the Library's device tab.
 */
class PathStepKeyTest {

    private val home = RegolithKey.Home
    private val library = RegolithKey.Library()
    private val films = RegolithKey.Library(folderId = 2)
    private val browse = RegolithKey.Browse()
    private val browseFilms = RegolithKey.Browse(folderId = 2)

    @Test
    fun `from a collection, a step opens the Library's collection, ringing the video in its own`() {
        val below = listOf(home, library, films)
        assertEquals(RegolithKey.Library(folderId = 5, highlightFileId = 9), pathStepKey(below, 5, 9, phone = false))
        assertEquals(RegolithKey.Library(folderId = 1), pathStepKey(below, 1, null, phone = false))
    }

    @Test
    fun `from Browse, a step stays in Browse`() {
        assertEquals(RegolithKey.Browse(5, highlightFileId = 9), pathStepKey(listOf(home, browse, browseFilms), 5, 9, phone = false))
    }

    @Test
    fun `from Home or Search, a step opens the Library`() {
        assertEquals(RegolithKey.Library(folderId = 5), pathStepKey(listOf(home), 5, null, phone = false))
        assertEquals(RegolithKey.Library(folderId = 5), pathStepKey(listOf(home, RegolithKey.Search()), 5, null, phone = false))
        // Search opened from a collection wall goes back to the Library too.
        assertEquals(RegolithKey.Library(folderId = 5), pathStepKey(listOf(home, library, films, RegolithKey.Search()), 5, null, phone = false))
    }

    @Test
    fun `the nearest tab decides, not the first`() {
        // A page opened from Browse, then a step into the Library, then a video there.
        val below = listOf(home, browseFilms, RegolithKey.TitleDetail(9), RegolithKey.Library(folderId = 5, highlightFileId = 9))
        assertEquals(RegolithKey.Library(folderId = 3), pathStepKey(below, 3, null, phone = false))
        // And the other way round.
        val back = listOf(home, films, RegolithKey.TitleDetail(9), RegolithKey.Browse(5, highlightFileId = 9))
        assertEquals(RegolithKey.Browse(3), pathStepKey(back, 3, null, phone = false))
    }

    @Test
    fun `a phone video's album opens on the device tab, with its chip lit`() {
        assertEquals(RegolithKey.Library(onDevice = true, phoneFolderId = 7), pathStepKey(listOf(home, RegolithKey.Library(onDevice = true)), 7, 9, phone = true))
        assertEquals(RegolithKey.Library(onDevice = true, phoneFolderId = 7), pathStepKey(listOf(home), 7, 9, phone = true))
    }

    @Test
    fun `a phone video opened from Browse keeps its album in Browse`() {
        assertEquals(RegolithKey.Browse(7, highlightFileId = 9), pathStepKey(listOf(home, browse, RegolithKey.Browse(7)), 7, 9, phone = true))
    }

    @Test
    fun `with nothing under the page, the Library`() {
        assertEquals(RegolithKey.Library(folderId = 5), pathStepKey(emptyList(), 5, null, phone = false))
    }
}
