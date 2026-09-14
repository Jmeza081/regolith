package com.regolith.desktop.ui.servers

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.regolith.desktop.AppGraph
import com.regolith.desktop.data.Connection
import com.regolith.desktop.data.SavedServer
import com.regolith.desktop.ui.components.ChaptersTextField
import com.regolith.desktop.ui.components.Eyebrow
import com.regolith.desktop.ui.components.PrimaryButton
import com.regolith.desktop.ui.components.ProblemCard
import com.regolith.desktop.ui.components.QuietButton
import com.regolith.ui.theme.RegolithTheme

/** The first screen: the remembered shares, and a form to add or edit one. */
@Composable
fun ServersScreen(graph: AppGraph, onConnected: (Connection) -> Unit) {
    val scope = rememberCoroutineScope()
    val vm = remember { ServersViewModel(graph, scope, onConnected) }
    val state by vm.state.collectAsState()
    // Enter in the form connects, as a Mac sheet's default button would.
    val enterConnects = Modifier.onPreviewKeyEvent { e ->
        if (e.type == KeyEventType.KeyDown && e.key == Key.Enter && state.canConnect) { vm.connect(); true } else false
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.width(520.dp).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 48.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Eyebrow("Regolith Chapters")
            Text("Your shares", style = MaterialTheme.typography.headlineSmall, color = RegolithTheme.colors.ink)
            Text(
                "Chapters are saved as a small file next to each film, where the phone app reads them.",
                style = MaterialTheme.typography.bodyMedium,
                color = RegolithTheme.colors.body,
            )

            if (state.saved.isNotEmpty()) {
                Column(Modifier.fillMaxWidth().testTag("servers_saved_list")) {
                    state.saved.forEach { server -> SavedRow(server, state, vm) }
                }
            } else if (state.loaded) {
                Text("No shares yet. Add one below.", style = MaterialTheme.typography.bodyMedium, color = RegolithTheme.colors.metadata)
            }

            Eyebrow(if (state.editingId != null) "Edit share" else "Add a share", Modifier.padding(top = 16.dp))
            ChaptersTextField(
                state.address, vm::onAddressChange, label = "Address",
                placeholder = "smb://192.168.1.24/media",
                modifier = Modifier.testTag("servers_address_field").then(enterConnects),
            )
            ChaptersTextField(
                state.username, vm::onUsernameChange, label = "Username",
                placeholder = "Leave blank to sign in as guest",
                modifier = Modifier.testTag("servers_username_field"),
            )
            ChaptersTextField(
                state.password, vm::onPasswordChange, label = "Password", password = true,
                enabled = state.username.isNotBlank(),
                placeholder = state.passwordPlaceholder,
                supportingText = if (state.username.isNotBlank()) "Kept in the macOS Keychain" else null,
                modifier = Modifier.testTag("servers_password_field").then(enterConnects),
            )
            state.problem?.let { ProblemCard(it, Modifier.testTag("servers_problem")) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                PrimaryButton(
                    if (state.editingId != null) "Save and connect" else "Connect", vm::connect,
                    enabled = state.canConnect, loading = state.connecting,
                    modifier = Modifier.testTag("servers_connect_button"),
                )
                if (state.editingId != null) QuietButton("Cancel", vm::cancelEdit, Modifier.testTag("servers_cancel_edit_button"))
            }
        }
    }

    state.removing?.let { server ->
        AlertDialog(
            onDismissRequest = vm::cancelRemove,
            title = { Text("Remove ${server.label}?") },
            text = {
                Text(
                    if (server.isGuest) "The app forgets this share. Its films and chapter files are not touched."
                    else "The app forgets this share and deletes its password from the Keychain. Its films and chapter files are not touched.",
                )
            },
            confirmButton = { PrimaryButton("Remove", vm::confirmRemove, Modifier.testTag("servers_remove_confirm_button")) },
            dismissButton = { QuietButton("Cancel", vm::cancelRemove, Modifier.testTag("servers_remove_cancel_button")) },
            containerColor = RegolithTheme.colors.surface,
            titleContentColor = RegolithTheme.colors.ink,
            textContentColor = RegolithTheme.colors.body,
        )
    }
}

@Composable
private fun SavedRow(server: SavedServer, state: ServersUiState, vm: ServersViewModel) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = !state.busy) { vm.connectSaved(server) }
            .testTag("servers_saved_row_${server.id}")
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(server.label, style = MaterialTheme.typography.bodyLarge, color = RegolithTheme.colors.ink)
            Text(if (server.isGuest) "Guest" else server.username, style = MaterialTheme.typography.bodySmall, color = RegolithTheme.colors.metadata)
        }
        if (state.connectingId == server.id) CircularProgressIndicator(Modifier.size(18.dp), color = RegolithTheme.colors.body, strokeWidth = 2.dp)
        QuietButton("Edit", { vm.edit(server) }, Modifier.testTag("servers_saved_edit_${server.id}"), enabled = !state.busy)
        QuietButton("Remove", { vm.askRemove(server) }, Modifier.testTag("servers_saved_remove_${server.id}"), enabled = !state.busy)
    }
    HorizontalDivider(color = RegolithTheme.colors.hairline)
}
