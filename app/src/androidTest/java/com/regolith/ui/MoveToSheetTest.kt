package com.regolith.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import com.regolith.ui.components.MoveChild
import com.regolith.ui.components.MoveSheetState
import com.regolith.ui.components.MoveToSheet
import com.regolith.ui.components.RAIL_AFTER
import com.regolith.ui.theme.RegolithTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * The move sheet with a folder too long to see at once: the button stays on
 * screen, the letter rail appears, and a letter brings its first folder up.
 * And its search: the folders found, where each one is, and what a tap on
 * one does.
 *
 * Driven through Compose's test API with a made-up folder, so it needs no
 * share — the sheet only ever draws what its [MoveSheetState] says.
 */
class MoveToSheetTest {

    @get:Rule
    val compose = createComposeRule()

    /** A folder of films, in the order the ViewModel sorts them: by name, ignoring case. No Q, no X. */
    private val films = listOf(
        "2001 A Space Odyssey", "Airplane", "Alien", "Aliens", "Amadeus", "Apocalypse Now", "Arrival",
        "Back to the Future", "Barbie", "Blade Runner", "Brazil", "Casablanca", "Chinatown", "Coco",
        "Dune", "Dunkirk", "Eraserhead", "E.T.", "Fargo", "Fight Club", "Gattaca", "Gladiator",
        "Goodfellas", "Heat", "Her", "Inception", "Interstellar", "Jaws", "Joker", "Kill Bill",
        "Labyrinth", "Léon", "Memento", "Metropolis", "Moon", "Nope", "Oldboy", "Oppenheimer",
        "Parasite", "Psycho", "Ran", "Rocky", "Se7en", "Signs", "Solaris", "Tenet", "The Thing",
        "Titanic", "Up", "Us", "Vertigo", "WALL-E", "Whiplash", "Yojimbo", "Zodiac",
    ).sortedBy { it.lowercase() }

    private fun sheet(names: List<String>, currentFolderId: Long = 1) = MoveSheetState(
        itemsLabel = "3 videos",
        shareName = "media",
        breadcrumb = "media / Films",
        currentFolderId = currentFolderId,
        children = names.mapIndexed { i, name -> MoveChild(folderId = 100L + i, name = name, meta = null) },
        chosenFolderId = currentFolderId,
        chosenName = "Films",
    )

    private fun show(state: MoveSheetState) = compose.setContent {
        RegolithTheme {
            MoveToSheet(state = state, onQueryChange = {}, onUp = {}, onOpen = {}, onChoose = {}, onNewFolder = {}, onConfirm = {}, onDismiss = {})
        }
    }

    private fun folderTag(name: String) = "move_sheet_folder_${100L + films.indexOf(name)}"

    /**
     * Off screen. A lazy list does not keep rows it has scrolled past, so
     * "not displayed" usually means "not there at all", which
     * `assertIsNotDisplayed` alone would report as a failure.
     */
    private fun assertOffScreen(tag: String) {
        if (compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()) compose.onNodeWithTag(tag).assertIsNotDisplayed()
    }

    @Test
    fun theMoveButtonStaysOnScreenUnderALongList() {
        show(sheet(films))
        // The list runs off the bottom...
        assertOffScreen(folderTag("Zodiac"))
        // ...and the button is on screen anyway, without a scroll.
        compose.onNodeWithTag("move_sheet_confirm").assertIsDisplayed()
    }

    @Test
    fun aLongListGetsTheRail() {
        show(sheet(films))
        compose.onNodeWithTag("move_sheet_rail").assertIsDisplayed()
    }

    @Test
    fun aListAtTheThresholdGetsNoRail() {
        show(sheet(films.take(RAIL_AFTER)))
        compose.onNodeWithTag("move_sheet_rail").assertDoesNotExist()
    }

    @Test
    fun aLetterBringsItsFirstFolderToTheTop() {
        show(sheet(films))
        compose.onNodeWithTag("move_sheet_rail_M").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(folderTag("Memento")).assertIsDisplayed()
        // The rows above M have gone off the top.
        assertOffScreen(folderTag("Airplane"))
        assertOffScreen("move_sheet_here")
    }

    @Test
    fun aDimmedLetterLeavesTheListWhereItIs() {
        show(sheet(films))
        compose.onNodeWithTag("move_sheet_rail_Q").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("move_sheet_here").assertIsDisplayed()
    }

    @Test
    fun theBubbleShowsWhileTheRailIsHeld() {
        show(sheet(films))
        compose.onNodeWithTag("move_sheet_rail_bubble").assertDoesNotExist()
        compose.onNodeWithTag("move_sheet_rail_T").performTouchInput { down(center) }
        compose.waitForIdle()
        compose.onNodeWithTag("move_sheet_rail_bubble").assertIsDisplayed()
        compose.onNodeWithTag(folderTag("Tenet")).assertIsDisplayed()
        compose.onNodeWithTag("move_sheet_rail_T").performTouchInput { up() }
        compose.waitForIdle()
        compose.onNodeWithTag("move_sheet_rail_bubble").assertDoesNotExist()
    }

    @Test
    fun walkingIntoAFolderStartsAtItsTop() {
        var state by mutableStateOf(sheet(films))
        compose.setContent {
            RegolithTheme {
                MoveToSheet(state = state, onQueryChange = {}, onUp = {}, onOpen = {}, onChoose = {}, onNewFolder = {}, onConfirm = {}, onDismiss = {})
            }
        }
        compose.onNodeWithTag("move_sheet_list").performScrollToIndex(40)
        compose.waitForIdle()
        assertOffScreen("move_sheet_here")

        // Into another folder just as long: it opens at its own top, not where the last was left.
        state = sheet(films, currentFolderId = 2)
        compose.waitForIdle()
        compose.onNodeWithTag("move_sheet_here").assertIsDisplayed()
    }

    /** Two folders called Sci-fi, found by a search for "sci". */
    private fun searching(state: MoveSheetState = sheet(films), results: List<MoveChild>? = listOf(
        MoveChild(folderId = 900, name = "Sci-fi", meta = "media / Films"),
        MoveChild(folderId = 901, name = "Sci-fi", meta = "media / TV / Anthologies / Season 01"),
    )) = state.copy(query = "sci", results = results)

    @Test
    fun whatIsTypedGoesToTheSearch() {
        val typed = mutableListOf<String>()
        compose.setContent {
            RegolithTheme {
                MoveToSheet(state = sheet(films), onQueryChange = { typed += it }, onUp = {}, onOpen = {}, onChoose = {}, onNewFolder = {}, onConfirm = {}, onDismiss = {})
            }
        }
        compose.onNodeWithTag("move_sheet_search_field").performTextInput("sci")
        assertEquals("sci", typed.last())
    }

    @Test
    fun aSearchShowsTheFoldersFoundAndWhereEachIs() {
        show(searching())
        compose.onNodeWithTag("move_sheet_result_900").assertIsDisplayed()
        compose.onNodeWithText("media / Films").assertIsDisplayed()
        compose.onNodeWithTag("move_sheet_result_901").assertIsDisplayed()
        // The walk gives way: no breadcrumb, no "here", no rail.
        compose.onNodeWithTag("move_sheet_breadcrumb").assertDoesNotExist()
        compose.onNodeWithTag("move_sheet_here").assertDoesNotExist()
        compose.onNodeWithTag("move_sheet_rail").assertDoesNotExist()
        // ...and so does the button: it names the folder chosen before the search.
        compose.onNodeWithTag("move_sheet_confirm").assertDoesNotExist()
    }

    @Test
    fun aResultIsChosenByItsRowAndWalkedIntoByItsChevron() {
        var chosen: Long? = null
        var opened: Long? = null
        compose.setContent {
            RegolithTheme {
                MoveToSheet(
                    state = searching(), onQueryChange = {}, onUp = {}, onOpen = { opened = it }, onChoose = { chosen = it },
                    onNewFolder = {}, onConfirm = {}, onDismiss = {},
                )
            }
        }
        compose.onNodeWithTag("move_sheet_result_901_choose").performClick()
        assertEquals(901L, chosen)
        compose.onNodeWithTag("move_sheet_result_900_open").performClick()
        assertEquals(900L, opened)
    }

    @Test
    fun aSearchThatFindsNothingSaysSo() {
        show(searching(results = emptyList()))
        compose.onNodeWithTag("move_sheet_no_results").assertIsDisplayed()
        compose.onNodeWithTag("move_sheet_no_results").assertTextEquals("No folder on media matches \u201csci\u201d.")
    }

    @Test
    fun aResultChosenIsBroughtIntoViewWhereItLives() {
        val zodiac = 100L + films.indexOf("Zodiac")
        show(sheet(films).copy(chosenFolderId = zodiac, chosenName = "Zodiac", reveal = zodiac))
        compose.waitForIdle()
        compose.onNodeWithTag(folderTag("Zodiac")).assertIsDisplayed()
        assertOffScreen("move_sheet_here")
    }
}
