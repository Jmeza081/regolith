package com.regolith.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToIndex
import com.regolith.ui.components.MoveChild
import com.regolith.ui.components.MoveSheetState
import com.regolith.ui.components.MoveToSheet
import com.regolith.ui.theme.RegolithTheme
import org.junit.Rule
import org.junit.Test

/**
 * The move sheet with a folder too long to see at once: the button stays on
 * screen, and walking into another folder starts at its top.
 *
 * Driven through Compose's test API with a made-up folder, so it needs no
 * share — the sheet only ever draws what its [MoveSheetState] says.
 */
class MoveToSheetTest {

    @get:Rule
    val compose = createComposeRule()

    /** A folder of films, in the order the ViewModel sorts them: by name, ignoring case. */
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
            MoveToSheet(state = state, onUp = {}, onOpen = {}, onChoose = {}, onNewFolder = {}, onConfirm = {}, onDismiss = {})
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
    fun walkingIntoAFolderStartsAtItsTop() {
        var state by mutableStateOf(sheet(films))
        compose.setContent {
            RegolithTheme {
                MoveToSheet(state = state, onUp = {}, onOpen = {}, onChoose = {}, onNewFolder = {}, onConfirm = {}, onDismiss = {})
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
}
