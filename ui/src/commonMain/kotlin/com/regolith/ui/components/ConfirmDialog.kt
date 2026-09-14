package com.regolith.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.regolith.ui.theme.DialogShape
import com.regolith.ui.theme.RegolithTheme
import com.regolith.ui.theme.Spacing
import com.regolith.ui.theme.TextStyles
import com.regolith.ui.theme.scaledDp

/**
 * The one destructive confirm (design: "DISCONNECT TOWER?"): a #0F0F0F
 * card at 20dp corners with a #2E2E2E hairline over a 72% scrim, the
 * title in Michroma 15, a body line that says what survives, the red
 * verb over a frosted way out.
 *
 * Use it for anything that throws away what the user made — a share's
 * media list, the chapters they wrote, an unsaved draft. House rule for
 * [keepLabel]: it names what is kept ("Keep it", "Keep mine"), never
 * "Cancel", so the two buttons read as two outcomes rather than yes/no.
 *
 * Tags: `<testTag>_dialog` on the card, `<testTag>_confirm_button` and
 * `<testTag>_keep_button` on the buttons.
 */
@Composable
fun ConfirmDialog(
    title: String,
    body: String,
    confirmLabel: String,
    keepLabel: String,
    onConfirm: () -> Unit,
    onKeep: () -> Unit,
    testTag: String,
) {
    val colors = RegolithTheme.colors
    Dialog(onDismissRequest = onKeep) {
        Column(
            Modifier.fillMaxWidth().background(colors.surface, DialogShape).border(1.dp, colors.raised, DialogShape).padding(Spacing.s18)
                // A Dialog is a window of its own, so the root Scaffold's
                // setting does not reach it; without this the buttons have
                // no resource id on Android and no test can find them.
                .exposeTestTags()
                .testTag("${testTag}_dialog"),
            verticalArrangement = Arrangement.spacedBy(Spacing.s12),
        ) {
            DisplayText(title, style = TextStyles.dialogTitle)
            Text(body, style = TextStyles.body, color = colors.body)
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.s8)) {
                DestructiveButton(text = confirmLabel, onClick = onConfirm, testTag = "${testTag}_confirm_button", modifier = Modifier.fillMaxWidth().height(48.scaledDp()))
                SecondaryButton(text = keepLabel, onClick = onKeep, testTag = "${testTag}_keep_button", modifier = Modifier.fillMaxWidth())
            }
        }
    }
}
