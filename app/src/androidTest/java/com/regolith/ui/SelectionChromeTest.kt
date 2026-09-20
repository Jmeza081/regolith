package com.regolith.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import com.regolith.R
import com.regolith.ui.components.ListRow
import com.regolith.ui.components.NavPill
import com.regolith.ui.components.RowLeading
import com.regolith.ui.components.RowTrailing
import com.regolith.ui.components.SelectionChromeState
import com.regolith.ui.components.SelectionSummaryTier
import com.regolith.ui.components.SelectionVerb
import com.regolith.ui.navigation.MainTab
import com.regolith.ui.theme.RegolithTheme
import com.regolith.ui.util.SelectionUiState
import dev.chrisbanes.haze.HazeState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * The selection gesture and chrome, driven through Compose's own test API.
 *
 * These cover what a screenshot cannot: that a HOLD and a TAP go to
 * different places, that a row covered by a picked ancestor does nothing
 * when tapped, and that Download refuses to be pressed when the batch
 * would not fit. Compose's test harness drives the composition directly,
 * so it does not depend on the device's accessibility service — which is
 * also why these are worth having when `uiautomator` is having a bad day.
 *
 * The toolbar half of this is the nav pill in its selection mode plus the
 * summary tier above it, which is how the chrome is actually assembled by
 * the nav graph — see [SelectionChromeState]. There is no separate
 * selection bar to test any more.
 */
class SelectionChromeTest {

    @get:Rule
    val compose = createComposeRule()

    // ── The gesture ────────────────────────────────────────────────────

    @Test
    fun aHoldStartsSelectionAndATapDoesNot() {
        var taps = 0
        var holds = 0
        compose.setContent {
            RegolithTheme {
                ListRow(
                    title = "S01E01.mkv",
                    meta = "1080p · 2.1 GB",
                    onClick = { taps++ },
                    onLongClick = { holds++ },
                    testTag = "browse_file_1",
                )
            }
        }

        compose.onNodeWithTag("browse_file_1").performClick()
        assertEquals("a tap opens the file", 1, taps)
        assertEquals("a tap must not arm selection", 0, holds)

        compose.onNodeWithTag("browse_file_1").performTouchInput { longClick() }
        assertEquals("a hold arms selection", 1, holds)
        assertEquals("the hold must not also open the file", 1, taps)
    }

    @Test
    fun aRowWithNoLongClickCannotStartASelection() {
        // Share rows are never selectable: a selection lives inside one share.
        // Holding one does not arm anything — there is no onLongClick to call —
        // and the gesture degrades to a tap, which is what plain `clickable`
        // did before `combinedClickable` replaced it. Unchanged behaviour, and
        // worth pinning so it stays unchanged.
        var taps = 0
        compose.setContent {
            RegolithTheme {
                ListRow(title = "media", onClick = { taps++ }, testTag = "browse_share_1")
            }
        }
        compose.onNodeWithTag("browse_share_1").performTouchInput { longClick() }
        assertEquals("the hold opens the share, as a tap would", 1, taps)
    }

    @Test
    fun whileSelectingATapTogglesInsteadOfOpening() {
        var opened = 0
        var toggled = 0
        compose.setContent {
            var selecting by remember { mutableStateOf(false) }
            RegolithTheme {
                ListRow(
                    title = "S01E01.mkv",
                    onClick = { if (selecting) toggled++ else opened++ },
                    onLongClick = { selecting = true },
                    trailing = if (selecting) RowTrailing.Checked else RowTrailing.Chevron,
                    testTag = "browse_file_1",
                )
            }
        }

        compose.onNodeWithTag("browse_file_1").performClick()
        assertEquals(1, opened)

        compose.onNodeWithTag("browse_file_1").performTouchInput { longClick() }
        compose.onNodeWithTag("browse_file_1").performClick()
        assertEquals("the second tap picks rather than opens", 1, toggled)
        assertEquals("and does not open anything", 1, opened)
    }

    @Test
    fun aCoveredRowDoesNothingWhenTapped() {
        // Its ancestor is picked, so the row is along for the ride. Offering
        // the tap would say otherwise.
        var acted = 0
        compose.setContent {
            RegolithTheme {
                ListRow(
                    title = "Extras",
                    meta = "Already inside your pick",
                    onClick = { },
                    onLongClick = { acted++ },
                    trailing = RowTrailing.Checked,
                    testTag = "browse_folder_2",
                )
            }
        }
        compose.onNodeWithTag("browse_folder_2").performClick()
        compose.onNodeWithText("Already inside your pick").assertIsDisplayed()
        assertEquals("the covered row's own tap is inert", 0, acted)
    }

    // ── Two targets on a folder row ─────────────────────────────────────
    //
    // The regression this guards is the one the owner hit: with the whole
    // row picking, walking down to a folder three levels in meant picking
    // every folder on the way, and a picked folder swallows everything
    // under it — so the first tap made the rest unreachable.

    @Test
    fun aFolderRowPicksFromTheBoxAndStillOpensFromTheRow() {
        var picked = 0
        var opened = 0
        compose.setContent {
            RegolithTheme {
                ListRow(
                    title = "Severance",
                    meta = "2 folders",
                    leading = RowLeading.PickBox(android.R.drawable.ic_menu_more, picked = false),
                    onClick = { opened++ },
                    onLeadingClick = { picked++ },
                    onLongClick = { },
                    trailing = RowTrailing.Chevron,
                    testTag = "browse_folder_7",
                )
            }
        }

        compose.onNodeWithTag("browse_folder_7_pick").performClick()
        assertEquals("the box picks", 1, picked)
        assertEquals("and does not walk in", 0, opened)

        compose.onNodeWithTag("browse_folder_7_open").performClick()
        assertEquals("the row still walks in while selecting", 1, opened)
        assertEquals("and does not pick", 1, picked)
    }

    @Test
    fun aFolderInsideAPickIsStillLiveInBothDirections() {
        // A folder that is coming because its parent is picked shows a check —
        // and the box stays live, because tapping it is how you take that
        // subtree back OUT. The row still opens, so you can look inside.
        var toggled = 0
        var opened = 0
        compose.setContent {
            RegolithTheme {
                ListRow(
                    title = "Extras",
                    meta = "Coming with the folder above",
                    leading = RowLeading.PickBox(android.R.drawable.ic_menu_more, picked = true),
                    onClick = { opened++ },
                    onLeadingClick = { toggled++ },
                    testTag = "browse_folder_8",
                )
            }
        }
        compose.onNodeWithTag("browse_folder_8_pick").performClick()
        assertEquals("the box takes the subtree back out", 1, toggled)
        compose.onNodeWithTag("browse_folder_8_open").performClick()
        assertEquals("and the row still opens", 1, opened)
    }

    @Test
    fun aFileRowIsOneTargetBecauseThereIsNowhereToWalkInto() {
        var toggled = 0
        compose.setContent {
            RegolithTheme {
                ListRow(
                    title = "S01E01.mkv",
                    onClick = { toggled++ },
                    onLongClick = { },
                    trailing = RowTrailing.Checked,
                    testTag = "browse_file_9",
                )
            }
        }
        compose.onNodeWithTag("browse_file_9").performClick()
        assertEquals(1, toggled)
    }

    // ── The chrome ─────────────────────────────────────────────────────
    //
    // The selection's toolbar is no longer a bar of its own: the nav pill
    // MORPHS into it, and the tally sits in a tier above. So these drive
    // the two pieces the nav graph draws — [NavPill] with a non-null
    // `selection`, and [SelectionSummaryTier] — which is also what makes
    // the assertions meaningful: a verb that is grey in the real pill is
    // grey here for the same reason.

    /** The nav graph's pair, composed the way the graph stacks them. */
    @Composable
    private fun Chrome(state: SelectionChromeState) {
        val haze = remember { HazeState() }
        RegolithTheme {
            Column {
                SelectionSummaryTier(state = state, hazeState = haze)
                NavPill(
                    selected = MainTab.entries.first(),
                    onSelect = { },
                    hazeState = haze,
                    selection = state,
                )
            }
        }
    }

    @Test
    fun thePillCanCarryADestructiveVerb() {
        // The On-this-device page reuses the chrome to REMOVE copies, so the
        // gesture reads the same wherever it is used and only the word and
        // the confirm change.
        var removed = 0
        compose.setContent {
            Chrome(
                SelectionChromeState(
                    verbs = listOf(
                        SelectionVerb(
                            label = "Remove",
                            icon = R.drawable.rg_ic_trash,
                            onClick = { removed++ },
                            testTag = "device_select_remove",
                            destructive = true,
                        ),
                    ),
                    onCancel = { },
                    summary = "3 videos · 6.3 GB",
                    detail = "The share keeps them — this frees the space here",
                ),
            )
        }

        // The pill uppercases its own labels (PillCell), so the verb reads
        // REMOVE on screen even though the state spells it "Remove".
        compose.onNodeWithText("REMOVE").assertIsDisplayed()
        compose.onNodeWithTag("device_select_remove").performClick()
        assertEquals(1, removed)
    }

    @Test
    fun aDestructiveVerbStillRefusesWhenNothingIsPicked() {
        var removed = 0
        compose.setContent {
            Chrome(
                SelectionChromeState(
                    verbs = listOf(
                        SelectionVerb(
                            label = "Remove",
                            icon = R.drawable.rg_ic_trash,
                            onClick = { removed++ },
                            testTag = "device_select_remove",
                            enabled = false,
                            destructive = true,
                        ),
                    ),
                    onCancel = { },
                    summary = "Nothing picked",
                    detail = "Hold or tap a copy to start",
                ),
            )
        }
        compose.onNodeWithTag("device_select_remove").assertIsNotEnabled()
        compose.onNodeWithTag("device_select_remove").performClick()
        assertEquals("a disabled verb must not fire", 0, removed)
    }

    @Test
    fun theChromeShowsTheTallyAndBothWaysOut() {
        var downloaded = 0
        var cancelled = 0
        val state = SelectionUiState(itemCount = 4, fileCount = 34, byteCount = 61_200_000_000)
        compose.setContent {
            Chrome(
                SelectionChromeState(
                    verbs = listOf(
                        SelectionVerb(
                            label = "Download",
                            icon = R.drawable.rg_ic_download,
                            onClick = { downloaded++ },
                            testTag = "browse_select_download",
                            enabled = state.canDownload,
                        ),
                    ),
                    onCancel = { cancelled++ },
                    summary = state.summary,
                    detail = state.detail,
                ),
            )
        }

        compose.onNodeWithTag("selection_summary").assertIsDisplayed()
        compose.onNodeWithText("34 videos · 61 GB").assertIsDisplayed()

        compose.onNodeWithTag("browse_select_download").assertIsEnabled().performClick()
        assertEquals(1, downloaded)
        // The way out is the circle beside the pill, not a cell inside it.
        compose.onNodeWithTag("nav_selection_cancel").performClick()
        assertEquals(1, cancelled)
    }

    @Test
    fun downloadIsDeadWhenTheBatchWouldNotFit() {
        var downloaded = 0
        val state = SelectionUiState(
            itemCount = 4, fileCount = 12, byteCount = 26_700_000_000,
            hasRoom = false, shortfall = 4_500_000_000,
        )
        compose.setContent {
            Chrome(downloadChrome(state) { downloaded++ })
        }

        // The grey verb and its reason are a pair: the tier carries the
        // sentence because the pill has nowhere to put one.
        compose.onNodeWithText("Not enough room · free 4.5 GB more").assertIsDisplayed()
        compose.onNodeWithTag("browse_select_download").assertIsNotEnabled()
        compose.onNodeWithTag("browse_select_download").performClick()
        assertEquals("a disabled Download must not fire", 0, downloaded)
    }

    @Test
    fun anEmptySelectionInvitesTheGestureAndCannotBeDownloaded() {
        val state = SelectionUiState()
        compose.setContent { Chrome(downloadChrome(state) { }) }

        compose.onNodeWithText("Nothing picked").assertIsDisplayed()
        compose.onNodeWithText("Hold or tap a video to start").assertIsDisplayed()
        compose.onNodeWithTag("browse_select_download").assertIsNotEnabled()
    }

    @Test
    fun eachScreensVerbTakesItsOwnTagSoTheyStayAddressable() {
        // Addressability moved with the toolbar: it used to be the bar's tag
        // prefix, and it is now the VERB's tag, which is what argent and
        // these tests reach for. Library and Browse both offer Download, so
        // the two must not answer to the same name.
        val state = SelectionUiState(itemCount = 1, fileCount = 1, byteCount = 2_100_000_000)
        compose.setContent {
            Chrome(
                SelectionChromeState(
                    verbs = listOf(
                        SelectionVerb(
                            label = "Download",
                            icon = R.drawable.rg_ic_download,
                            onClick = { },
                            testTag = "library_select_download",
                            enabled = state.canDownload,
                        ),
                    ),
                    onCancel = { },
                    summary = state.summary,
                    detail = state.detail,
                ),
            )
        }
        compose.onNodeWithTag("nav_pill").assertIsDisplayed()
        compose.onNodeWithTag("library_select_download").assertIsDisplayed()
        compose.onNodeWithTag("browse_select_download").assertDoesNotExist()
    }

    /** Browse's Download-only chrome, which three of these need verbatim. */
    private fun downloadChrome(state: SelectionUiState, onDownload: () -> Unit) =
        SelectionChromeState(
            verbs = listOf(
                SelectionVerb(
                    label = "Download",
                    icon = R.drawable.rg_ic_download,
                    onClick = onDownload,
                    testTag = "browse_select_download",
                    enabled = state.canDownload,
                ),
            ),
            onCancel = { },
            summary = state.summary,
            detail = state.detail,
        )
}
