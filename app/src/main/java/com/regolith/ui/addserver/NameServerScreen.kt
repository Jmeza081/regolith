package com.regolith.ui.addserver

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.regolith.domain.model.MAX_SERVER_NAME
import com.regolith.ui.components.PrimaryButton
import com.regolith.ui.components.RegolithTextField
import com.regolith.ui.components.TopBar
import com.regolith.ui.theme.RegolithTheme
import com.regolith.ui.theme.Spacing
import com.regolith.ui.theme.TextStyles

/**
 * "Name this server": the step between connecting and choosing shares.
 *
 * It exists because the app's own name for a box added by address IS the
 * address — `192.168.4.73` reads the same as every other NAS on the
 * network, and there is no later moment in the flow where the user is
 * thinking about which box this is. Asking here costs one Continue tap to
 * anyone who does not care, because the field is optional: blank keeps the
 * name the app derived, shown as the placeholder.
 *
 * The name can be changed afterwards in Settings; nothing is keyed to it.
 */
@Composable
fun NameServerScreen(
    viewModel: NameServerViewModel,
    onBack: () -> Unit,
    onContinue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(state.saved) {
        if (!state.saved) return@LaunchedEffect
        viewModel.consumeNavigation()
        onContinue()
    }
    NameServerContent(
        name = state.name,
        address = state.address,
        suggestion = state.suggestion,
        loaded = state.loaded,
        onNameChange = viewModel::onNameChange,
        onSave = viewModel::save,
        onBack = onBack,
        modifier = modifier,
    )
}

/**
 * The screen without its ViewModel, in the shape [ChapterEditorContent]
 * established: state in, callbacks out.
 *
 * Split out so it can be driven by a Compose test. That is not only tidiness
 * — this project's emulator has a habit of serving a stale accessibility
 * tree, and a screen that can only be reached by tapping through four others
 * is a screen that then cannot be checked at all.
 *
 * @param suggestion the name the app derived; shown as the placeholder,
 *   because it is what Continue keeps when nothing is typed.
 * @param loaded false until the server row has been read; Continue would
 *   have nothing to name before then.
 */
@Composable
fun NameServerContent(
    name: String,
    address: String,
    suggestion: String,
    loaded: Boolean,
    onNameChange: (String) -> Unit,
    onSave: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RegolithTheme.colors
    val focus = remember { FocusRequester() }
    // The keyboard is the point of this screen; making the user tap the
    // field first is a tap that never had anything else to be.
    LaunchedEffect(loaded) { if (loaded) focus.requestFocus() }
    Column(modifier.fillMaxSize().navigationBarsPadding().imePadding().testTag("addserver_name_screen")) {
        TopBar(
            title = "Name this server",
            onBack = onBack,
            subtitle = address.takeIf { it.isNotBlank() },
            subtitleMuted = true,
        )
        Column(
            Modifier.weight(1f).padding(horizontal = Spacing.s18),
            verticalArrangement = Arrangement.spacedBy(Spacing.s12),
        ) {
            Text(
                "Give it a name you'll recognise in Library and Settings. Leave this blank to keep $suggestion.",
                style = TextStyles.body,
                color = colors.body,
            )
            RegolithTextField(
                value = name,
                onValueChange = { onNameChange(it.take(MAX_SERVER_NAME)) },
                label = "Name",
                placeholder = suggestion,
                testTag = "addserver_name_field",
                modifier = Modifier.focusRequester(focus),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onSave() }),
            )
        }
        PrimaryButton(
            text = "Continue",
            onClick = onSave,
            enabled = loaded,
            testTag = "addserver_name_continue_button",
            modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.s18, vertical = Spacing.s18),
        )
    }
}
