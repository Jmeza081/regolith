package com.regolith.desktop.ui.servers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.regolith.desktop.AppGraph
import com.regolith.desktop.data.Connection
import com.regolith.desktop.data.SavedServer
import com.regolith.desktop.ui.components.clickOnly
import com.regolith.ui.components.ConfirmDialog
import com.regolith.ui.components.DisplayText
import com.regolith.ui.components.ErrorCard
import com.regolith.ui.components.Eyebrow
import com.regolith.ui.components.ListRow
import com.regolith.ui.components.PrimaryButton
import com.regolith.ui.components.RegolithIcons
import com.regolith.ui.components.RegolithTextField
import com.regolith.ui.components.RowLeading
import com.regolith.ui.components.RowTrailing
import com.regolith.ui.components.SurfaceCard
import com.regolith.ui.components.TertiaryButton
import com.regolith.ui.theme.RegolithTheme
import com.regolith.ui.theme.Spacing
import com.regolith.ui.theme.TextStyles

/** The first screen: the remembered shares, and a form to add or edit one. */
@Composable
fun ServersScreen(graph: AppGraph, onConnected: (Connection) -> Unit) {
    val scope = rememberCoroutineScope()
    val vm = remember { ServersViewModel(graph, scope, onConnected) }
    val state by vm.state.collectAsState()
    val colors = RegolithTheme.colors
    // Enter in the form connects, as a Mac sheet's default button would.
    val enterConnects = Modifier.onPreviewKeyEvent { e ->
        if (e.type == KeyEventType.KeyDown && e.key == Key.Enter && state.canConnect) { vm.connect(); true } else false
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.width(520.dp).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 48.dp),
            verticalArrangement = Arrangement.spacedBy(Spacing.s12),
        ) {
            Eyebrow("Regolith Chapters")
            DisplayText("Your shares", style = TextStyles.wordmark)
            Text(
                "Chapters are saved as a small file next to each film, where the phone app reads them.",
                style = TextStyles.body,
                color = colors.body,
            )

            if (state.saved.isNotEmpty()) {
                SurfaceCard(Modifier.fillMaxWidth().testTag("servers_saved_list"), contentPadding = PaddingValues(horizontal = Spacing.s12)) {
                    state.saved.forEach { server -> SavedRow(server, state, vm) }
                }
            } else if (state.loaded) {
                Text("No shares yet. Add one below.", style = TextStyles.body, color = colors.metadata)
            }

            Eyebrow(if (state.editingId != null) "Edit share" else "Add a share", Modifier.padding(top = Spacing.s18))
            RegolithTextField(
                state.address, vm::onAddressChange, label = "Address", testTag = "servers_address_field",
                placeholder = "smb://192.168.1.24/media",
                modifier = enterConnects,
            )
            RegolithTextField(
                state.username, vm::onUsernameChange, label = "Username", testTag = "servers_username_field",
                placeholder = "Leave blank to sign in as guest",
            )
            RegolithTextField(
                state.password, vm::onPasswordChange, label = "Password", testTag = "servers_password_field",
                enabled = state.username.isNotBlank(),
                placeholder = state.passwordPlaceholder,
                visualTransformation = PasswordVisualTransformation(),
                modifier = enterConnects,
            )
            if (state.username.isNotBlank()) {
                Text("Kept in the macOS Keychain", style = TextStyles.meta, color = colors.metadata)
            }
            state.problem?.let { ErrorCard(it.message, testTag = "servers_problem", detail = it.detail) }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.s8)) {
                PrimaryButton(
                    if (state.editingId != null) "Save and connect" else "Connect", vm::connect,
                    testTag = "servers_connect_button",
                    enabled = state.canConnect, loading = state.connecting,
                    modifier = Modifier.clickOnly(),
                )
                if (state.editingId != null) {
                    TertiaryButton("Cancel", vm::cancelEdit, testTag = "servers_cancel_edit_button", modifier = Modifier.clickOnly())
                }
            }
        }
    }

    state.removing?.let { server ->
        ConfirmDialog(
            title = "Remove ${server.label}?",
            body = if (server.isGuest) {
                "The app forgets this share. Its films and chapter files are not touched."
            } else {
                "The app forgets this share and deletes its password from the Keychain. Its films and chapter files are not touched."
            },
            confirmLabel = "Remove",
            keepLabel = "Keep it",
            onConfirm = vm::confirmRemove,
            onKeep = vm::cancelRemove,
            testTag = "servers_remove",
        )
    }
}

/** One remembered share: click it to connect; Edit and Remove sit beside it. */
@Composable
private fun SavedRow(server: SavedServer, state: ServersUiState, vm: ServersViewModel) {
    val colors = RegolithTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        ListRow(
            title = server.label,
            meta = if (server.isGuest) "Guest" else server.username,
            onClick = { if (!state.busy) vm.connectSaved(server) },
            testTag = "servers_saved_row_${server.id}",
            leading = RowLeading.Glyph(rememberVectorPainter(RegolithIcons.Server)),
            trailing = RowTrailing.None,
            modifier = Modifier.weight(1f),
        )
        if (state.connectingId == server.id) {
            CircularProgressIndicator(Modifier.size(18.dp), color = colors.body, strokeWidth = 2.dp)
        }
        TertiaryButton("Edit", { vm.edit(server) }, testTag = "servers_saved_edit_${server.id}", enabled = !state.busy, modifier = Modifier.clickOnly())
        TertiaryButton(
            "Remove", { vm.askRemove(server) }, testTag = "servers_saved_remove_${server.id}",
            enabled = !state.busy, ink = colors.accent, modifier = Modifier.clickOnly(),
        )
    }
}
