package com.regolith.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.regolith.R
import com.regolith.ui.theme.DialogMaxWidth
import com.regolith.ui.theme.DialogShape
import com.regolith.ui.theme.RegolithTheme
import com.regolith.ui.theme.Spacing
import com.regolith.ui.theme.scaledDp
import com.regolith.ui.theme.TextStyles

/**
 * A dialog that asks for one line of text: the same #0F0F0F card and
 * hairline as [ConfirmDialog], with a [RegolithTextField] where that one
 * has a body paragraph.
 *
 * Use it for renaming something the user owns — a server, a saved view —
 * where a whole screen would be more ceremony than the change deserves.
 * For a destructive question use [ConfirmDialog]; for a list of answers
 * use [RegolithSheet].
 *
 * The draft lives HERE rather than in a ViewModel. A dialog that is open
 * is a dialog the user has not committed yet, so every keystroke reaching
 * the state holder would be a change they never asked to keep — and
 * cancelling would have to undo it. [rememberSaveable] still carries the
 * draft through a rotation, which is the only reason a ViewModel would
 * have been tempting.
 *
 * Tags: `<testTag>_dialog` on the card, `<testTag>_field` on the input,
 * `<testTag>_confirm_button` and `<testTag>_cancel_button` on the buttons.
 *
 * @param initialValue what the field starts with; empty is fine when
 *   [placeholder] already shows what happens if nothing is typed.
 * @param note a line under the buttons naming the thing being renamed
 *   ("192.168.4.73 · SMB"), so the dialog says WHICH one this is.
 * @param maxLength the field stops accepting characters here, rather than
 *   letting the user type something that is silently cut on save.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun PromptDialog(
    title: String,
    label: String,
    initialValue: String,
    confirmLabel: String,
    onConfirm: (String) -> Unit,
    onCancel: () -> Unit,
    testTag: String,
    placeholder: String? = null,
    note: String? = null,
    cancelLabel: String = "Cancel",
    maxLength: Int = Int.MAX_VALUE,
) {
    val colors = RegolithTheme.colors
    var draft by rememberSaveable(initialValue) { mutableStateOf(initialValue) }
    val focus = remember { FocusRequester() }
    // The keyboard is the whole point of this dialog; making the user tap
    // the field first is a tap that never had anything else to be.
    LaunchedEffect(Unit) { focus.requestFocus() }
    // usePlatformDefaultWidth = false: Compose's default caps a dialog at the
    // platform's own width (~280dp), which left this card noticeably narrower
    // than every other surface in the app. Off, the window is full-width and
    // the card takes the app's own 18dp screen gutter instead, so a filename
    // has the same room to breathe here as it does in the row behind it.
    Dialog(onDismissRequest = onCancel, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxWidth().padding(horizontal = Spacing.s18), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = DialogMaxWidth).fillMaxWidth()
                .background(colors.surface, DialogShape).border(1.dp, colors.raised, DialogShape).padding(Spacing.s18)
                // A Dialog is its own window, so the root Scaffold's setting
                // does not reach it and nothing inside would have a resource id.
                .semantics { testTagsAsResourceId = true }
                .testTag("${testTag}_dialog"),
            verticalArrangement = Arrangement.spacedBy(Spacing.s12),
        ) {
            DisplayText(title, style = TextStyles.dialogTitle)
            RegolithTextField(
                value = draft,
                // take() rather than a length guard: a guard rejects an
                // over-long paste outright, which reads as a dead field.
                onValueChange = { draft = it.take(maxLength) },
                label = label,
                placeholder = placeholder,
                testTag = "${testTag}_field",
                modifier = Modifier.focusRequester(focus),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onConfirm(draft) }),
                // Only there when there is something to clear: a permanent X
                // beside an empty field is a control that does nothing. The
                // field keeps focus, so clearing leaves you typing rather
                // than reaching for the field again.
                trailingIcon = if (draft.isEmpty()) {
                    null
                } else {
                    {
                        Box(
                            Modifier
                                .size(44.dp)
                                .clickable(interactionSource = null, indication = null, onClick = { draft = "" })
                                .testTag("${testTag}_clear"),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                painterResource(R.drawable.rg_ic_close_small),
                                contentDescription = "Clear the name",
                                tint = colors.metadata,
                                modifier = Modifier.size(16.scaledDp()),
                            )
                        }
                    }
                },
            )
            if (note != null) Text(note, style = TextStyles.meta12, color = colors.metadata)
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s8)) {
                SecondaryButton(text = cancelLabel, onClick = onCancel, testTag = "${testTag}_cancel_button", modifier = Modifier.weight(1f))
                PrimaryButton(text = confirmLabel, onClick = { onConfirm(draft) }, testTag = "${testTag}_confirm_button", modifier = Modifier.weight(1f))
            }
        }
        }
    }
}
