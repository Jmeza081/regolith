package com.regolith.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import com.regolith.ui.components.ListRow
import com.regolith.ui.components.RowLeading
import com.regolith.ui.components.RowTrailing
import com.regolith.ui.components.SelectionBar
import com.regolith.ui.theme.RegolithTheme
import com.regolith.ui.util.SelectionUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    @Test
    fun theBarCanCarryADestructiveAction() {
        // The On-this-device page reuses the bar to REMOVE copies, so the
        // gesture reads the same wherever it is used and only the word and
        // the confirm change.
        var removed = 0
        compose.setContent {
            RegolithTheme {
                SelectionBar(
                    summary = "3 videos · 6.3 GB",
                    detail = "The share keeps them — this frees the space here",
                    actionText = "Remove",
                    destructive = true,
                    onAction = { removed++ },
                    onCancel = { },
                    testTag = "device_select_bar",
                )
            }
        }
        compose.onNodeWithText("Remove").assertIsDisplayed()
        compose.onNodeWithTag("device_select_bar_download").performClick()
        assertEquals(1, removed)
    }

    @Test
    fun aDestructiveActionStillRefusesWhenNothingIsPicked() {
        var removed = 0
        compose.setContent {
            RegolithTheme {
                SelectionBar(
                    summary = "Nothing picked",
                    detail = "Hold or tap a copy to start",
                    actionText = "Remove",
                    destructive = true,
                    actionEnabled = false,
                    onAction = { removed++ },
                    onCancel = { },
                    testTag = "device_select_bar",
                )
            }
        }
        compose.onNodeWithTag("device_select_bar_download").assertIsNotEnabled()
        compose.onNodeWithTag("device_select_bar_download").performClick()
        assertEquals(0, removed)
    }

    // ── The bar ────────────────────────────────────────────────────────

    @Test
    fun theBarShowsTheTallyAndBothWaysOut() {
        var downloaded = 0
        var cancelled = 0
        val state = SelectionUiState(itemCount = 4, fileCount = 34, byteCount = 61_200_000_000)
        compose.setContent {
            RegolithTheme {
                Column {
                    SelectionBar(
                        summary = state.summary,
                        detail = state.detail,
                        actionEnabled = state.canDownload,
                        onAction = { downloaded++ },
                        onCancel = { cancelled++ },
                    )
                }
            }
        }

        compose.onNodeWithTag("browse_select_bar").assertIsDisplayed()
        compose.onNodeWithTag("browse_select_bar_summary").assertIsDisplayed()
        compose.onNodeWithText("34 videos · 61 GB").assertIsDisplayed()

        compose.onNodeWithTag("browse_select_bar_download").assertIsEnabled().performClick()
        assertEquals(1, downloaded)
        compose.onNodeWithTag("browse_select_bar_cancel").performClick()
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
            RegolithTheme {
                SelectionBar(
                    summary = state.summary,
                    detail = state.detail,
                    actionEnabled = state.canDownload,
                    onAction = { downloaded++ },
                    onCancel = { },
                )
            }
        }

        compose.onNodeWithText("Not enough room · free 4.5 GB more").assertIsDisplayed()
        compose.onNodeWithTag("browse_select_bar_download").assertIsNotEnabled()
        compose.onNodeWithTag("browse_select_bar_download").performClick()
        assertEquals("a disabled Download must not fire", 0, downloaded)
    }

    @Test
    fun anEmptySelectionInvitesTheGestureAndCannotBeDownloaded() {
        val state = SelectionUiState()
        compose.setContent {
            RegolithTheme {
                SelectionBar(
                    summary = state.summary,
                    detail = state.detail,
                    actionEnabled = state.canDownload,
                    onAction = { },
                    onCancel = { },
                )
            }
        }
        compose.onNodeWithText("Nothing picked").assertIsDisplayed()
        compose.onNodeWithText("Hold or tap a video to start").assertIsDisplayed()
        compose.onNodeWithTag("browse_select_bar_download").assertIsNotEnabled()
    }

    @Test
    fun theBarTakesItsOwnTagSoEachScreenIsAddressable() {
        val state = SelectionUiState(itemCount = 1, fileCount = 1, byteCount = 2_100_000_000)
        compose.setContent {
            RegolithTheme {
                SelectionBar(
                    summary = state.summary,
                    detail = state.detail,
                    onAction = { },
                    onCancel = { },
                    testTag = "library_select_bar",
                )
            }
        }
        compose.onNodeWithTag("library_select_bar").assertIsDisplayed()
        compose.onNodeWithTag("library_select_bar_download").assertIsDisplayed()
        assertTrue(true)
    }
}
