package com.regolith.desktop.ui.servers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.regolith.desktop.AppGraph
import com.regolith.desktop.data.Connection
import com.regolith.desktop.ui.components.ChaptersTextField
import com.regolith.desktop.ui.components.Eyebrow
import com.regolith.desktop.ui.components.PrimaryButton
import com.regolith.desktop.ui.components.ProblemCard
import com.regolith.desktop.ui.theme.Palette

/** The first screen: where the films are, and how to sign in. */
@Composable
fun ServersScreen(graph: AppGraph, onConnected: (Connection) -> Unit) {
    val scope = rememberCoroutineScope()
    val vm = remember { ServersViewModel(graph, scope, onConnected) }
    val state by vm.state.collectAsState()

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(Modifier.width(440.dp).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Eyebrow("Regolith Chapters")
            Text("Connect to a share", style = MaterialTheme.typography.headlineSmall, color = Palette.Ink)
            Text(
                "Chapters are saved as a small file next to each film, where the phone app reads them.",
                style = MaterialTheme.typography.bodyMedium,
                color = Palette.Body,
            )
            ChaptersTextField(
                state.address, vm::onAddressChange, label = "Address",
                placeholder = "smb://192.168.1.24/media",
                modifier = Modifier.testTag("servers_address_field"),
            )
            ChaptersTextField(
                state.username, vm::onUsernameChange, label = "Username",
                placeholder = "Leave blank to sign in as guest",
                modifier = Modifier.testTag("servers_username_field"),
            )
            ChaptersTextField(
                state.password, vm::onPasswordChange, label = "Password", password = true,
                enabled = state.username.isNotBlank(),
                modifier = Modifier.testTag("servers_password_field"),
            )
            state.problem?.let { ProblemCard(it, Modifier.testTag("servers_problem")) }
            PrimaryButton(
                "Connect", vm::connect,
                enabled = state.canConnect, loading = state.connecting,
                modifier = Modifier.testTag("servers_connect_button"),
            )
        }
    }
}
