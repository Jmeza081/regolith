package com.regolith.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.regolith.ui.addserver.NameServerContent
import com.regolith.ui.theme.RegolithTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * The "Name this server" step of Add Server.
 *
 * The behaviour worth pinning is that the step is OPTIONAL: the field is
 * empty, the derived name is only a placeholder, and Continue on an
 * untouched field is a valid answer meaning "keep what you worked out".
 * If that ever regresses into a required field, adding a server grows a
 * mandatory question it never had.
 */
class NameServerScreenTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun theFieldIsEmptyAndTheDerivedNameIsOnlyAPlaceholder() {
        compose.setContent {
            RegolithTheme {
                NameServerContent(
                    name = "",
                    address = "10.0.2.2:1445",
                    suggestion = "TOWER",
                    loaded = true,
                    onNameChange = { },
                    onSave = { },
                    onBack = { },
                )
            }
        }

        compose.onNodeWithTag("addserver_name_screen").assertIsDisplayed()
        // The address says WHICH box is being named…
        compose.onNodeWithText("10.0.2.2:1445").assertIsDisplayed()
        // …and the derived name appears as a placeholder, not as a value
        // the user has to clear before typing.
        compose.onNodeWithText("TOWER").assertIsDisplayed()
        compose.onNodeWithText("Give it a name you'll recognise in Library and Settings. Leave this blank to keep TOWER.").assertIsDisplayed()
    }

    @Test
    fun continueWithNothingTypedIsAValidAnswer() {
        var saved = 0
        compose.setContent {
            RegolithTheme {
                NameServerContent(
                    name = "",
                    address = "10.0.2.2:1445",
                    suggestion = "TOWER",
                    loaded = true,
                    onNameChange = { },
                    onSave = { saved++ },
                    onBack = { },
                )
            }
        }

        compose.onNodeWithTag("addserver_name_continue_button").assertIsEnabled().performClick()
        assertEquals("an untouched field must not block the flow", 1, saved)
    }

    @Test
    fun typingReportsTheName() {
        var typed = ""
        compose.setContent {
            RegolithTheme {
                NameServerContent(
                    name = typed,
                    address = "10.0.2.2:1445",
                    suggestion = "TOWER",
                    loaded = true,
                    onNameChange = { typed = it },
                    onSave = { },
                    onBack = { },
                )
            }
        }

        compose.onNodeWithTag("addserver_name_field").performTextInput("Living room NAS")
        assertEquals("Living room NAS", typed)
    }

    @Test
    fun continueIsDeadUntilThereIsAServerToName() {
        var saved = 0
        compose.setContent {
            RegolithTheme {
                NameServerContent(
                    name = "",
                    address = "",
                    suggestion = "",
                    loaded = false,
                    onNameChange = { },
                    onSave = { saved++ },
                    onBack = { },
                )
            }
        }

        compose.onNodeWithTag("addserver_name_continue_button").assertIsNotEnabled().performClick()
        assertEquals("nothing has been read yet; there is nothing to name", 0, saved)
    }
}
