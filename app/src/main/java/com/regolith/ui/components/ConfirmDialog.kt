package com.regolith.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.regolith.ui.theme.DialogMaxWidth
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
@OptIn(ExperimentalComposeUiApi::class)
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
    // Full-width window, then the app's own 18dp gutter — see [PromptDialog]
    // for why: Compose's default caps a dialog at the platform's width, which
    // is narrower than every other surface in the app.
    Dialog(onDismissRequest = onKeep, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxWidth().padding(horizontal = Spacing.s18), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = DialogMaxWidth).fillMaxWidth()
                .background(colors.surface, DialogShape).border(1.dp, colors.raised, DialogShape).padding(Spacing.s18)
                // A Dialog is a window of its own, so the root Scaffold's
                // setting does not reach it; without this the buttons have
                // no resource id and no test can find them.
                .semantics { testTagsAsResourceId = true }
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
}
