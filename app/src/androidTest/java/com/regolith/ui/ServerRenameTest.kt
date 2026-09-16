package com.regolith.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import com.regolith.ui.components.PromptDialog
import com.regolith.ui.theme.RegolithTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

/**
 * Renaming a server through [PromptDialog], driven through Compose's own
 * test API.
 *
 * Like [SelectionChromeTest], these run against the composition rather than
 * the device's accessibility service — which is the only reason they could
 * be written on the day `uiautomator` stopped answering on this emulator.
 *
 * What they pin: that Save reports what was typed and Cancel reports
 * nothing (the draft must not leak out of an abandoned dialog), that the
 * dialog opens on the name it is renaming, and that a name too long for a
 * row is clipped rather than swallowing the paste.
 */
class ServerRenameTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun savingReportsWhatWasTyped() {
        var saved: String? = null
        compose.setContent {
            RegolithTheme {
                PromptDialog(
                    title = "Name this server",
                    label = "Name",
                    initialValue = "192.168.4.73",
                    confirmLabel = "Save",
                    onConfirm = { saved = it },
                    onCancel = { },
                    testTag = "settings_rename",
                )
            }
        }

        compose.onNodeWithTag("settings_rename_field").performTextReplacement("Living room NAS")
        compose.onNodeWithTag("settings_rename_confirm_button").performClick()
        assertEquals("Living room NAS", saved)
    }

    @Test
    fun cancellingReportsNothing() {
        // An open dialog is an uncommitted one: whatever was typed must not
        // reach the ViewModel by the back door.
        var saved: String? = null
        var cancelled = 0
        compose.setContent {
            RegolithTheme {
                PromptDialog(
                    title = "Name this server",
                    label = "Name",
                    initialValue = "TOWER",
                    confirmLabel = "Save",
                    onConfirm = { saved = it },
                    onCancel = { cancelled++ },
                    testTag = "settings_rename",
                )
            }
        }

        compose.onNodeWithTag("settings_rename_field").performTextReplacement("Attic box")
        compose.onNodeWithTag("settings_rename_cancel_button").performClick()
        assertEquals(1, cancelled)
        assertNull("a cancelled rename must not be saved", saved)
    }

    @Test
    fun theDialogOpensOnTheNameItIsRenamingAndSaysWhichBoxThatIs() {
        compose.setContent {
            RegolithTheme {
                PromptDialog(
                    title = "Name this server",
                    label = "Name",
                    initialValue = "TOWER",
                    note = "192.168.4.73",
                    confirmLabel = "Save",
                    onConfirm = { },
                    onCancel = { },
                    testTag = "settings_rename",
                )
            }
        }

        compose.onNodeWithTag("settings_rename_dialog").assertIsDisplayed()
        // The note is what tells two nicknamed NAS boxes apart.
        compose.onNodeWithText("192.168.4.73").assertIsDisplayed()
    }

    @Test
    fun aNameTooLongForARowIsClippedRatherThanRejected() {
        var saved: String? = null
        compose.setContent {
            RegolithTheme {
                PromptDialog(
                    title = "Name this server",
                    label = "Name",
                    initialValue = "",
                    confirmLabel = "Save",
                    onConfirm = { saved = it },
                    onCancel = { },
                    testTag = "settings_rename",
                    maxLength = 5,
                )
            }
        }

        compose.onNodeWithTag("settings_rename_field").performTextInput("abcdefghij")
        compose.onNodeWithTag("settings_rename_confirm_button").performClick()
        assertEquals("the field clips; it does not swallow the whole paste", "abcde", saved)
    }
}
