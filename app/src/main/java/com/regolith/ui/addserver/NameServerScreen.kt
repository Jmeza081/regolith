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
    val colors = RegolithTheme.colors
    val focus = remember { FocusRequester() }
    LaunchedEffect(state.loaded) { if (state.loaded) focus.requestFocus() }
    LaunchedEffect(state.saved) {
        if (!state.saved) return@LaunchedEffect
        viewModel.consumeNavigation()
        onContinue()
    }
    Column(modifier.fillMaxSize().navigationBarsPadding().imePadding().testTag("addserver_name_screen")) {
        TopBar(
            title = "Name this server",
            onBack = onBack,
            subtitle = state.address.takeIf { it.isNotBlank() },
            subtitleMuted = true,
        )
        Column(
            Modifier.weight(1f).padding(horizontal = Spacing.s18),
            verticalArrangement = Arrangement.spacedBy(Spacing.s12),
        ) {
            Text(
                "Give it a name you'll recognise in Library and Settings. Leave this blank to keep ${state.suggestion}.",
                style = TextStyles.body,
                color = colors.body,
            )
            RegolithTextField(
                value = state.name,
                onValueChange = { viewModel.onNameChange(it.take(MAX_SERVER_NAME)) },
                label = "Name",
                placeholder = state.suggestion,
                testTag = "addserver_name_field",
                modifier = Modifier.focusRequester(focus),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { viewModel.save() }),
            )
        }
        PrimaryButton(
            text = "Continue",
            onClick = viewModel::save,
            enabled = state.loaded,
            testTag = "addserver_name_continue_button",
            modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.s18, vertical = Spacing.s18),
        )
    }
}
