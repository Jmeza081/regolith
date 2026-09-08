package com.regolith.ui.addserver

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.composables.icons.lucide.R as LucideR
import com.regolith.ui.components.DisplayText
import com.regolith.ui.components.ErrorCard
import com.regolith.ui.components.PrimaryButton
import com.regolith.ui.components.RegolithSwitch
import com.regolith.ui.components.RegolithTextField
import com.regolith.ui.components.SecondaryButton
import com.regolith.ui.components.TertiaryButton
import com.regolith.ui.components.TopBar
import com.regolith.ui.theme.RegolithTheme
import com.regolith.ui.theme.Spacing
import com.regolith.ui.theme.TextStyles

/**
 * "Enter an address" (design section 03): address, username, password,
 * save-credentials, Connect. The Connecting and Sign-in-failed screens are
 * states of this one so the typed values survive a failure.
 */
@Composable
fun ManualEntryScreen(
    viewModel: AddServerViewModel,
    onBack: () -> Unit,
    onConnected: (serverId: Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(state.connectedServerId) {
        val id = state.connectedServerId ?: return@LaunchedEffect
        viewModel.consumeNavigation()
        onConnected(id)
    }

    if (state.phase == AddServerUiState.Phase.Connecting) {
        ConnectingContent(address = state.address, onCancel = viewModel::cancelConnect, modifier = modifier)
        return
    }

    var showPassword by remember { mutableStateOf(false) }
    val colors = RegolithTheme.colors

    // Android 17 blocks LAN connections until the user grants local-network
    // access. Ask on the first Connect, then carry on with the connect.
    val context = LocalContext.current
    var permissionDenied by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permissionDenied = !granted
        if (granted) viewModel.connect()
    }
    fun connectWithPermission() {
        if (Build.VERSION.SDK_INT >= LOCAL_NETWORK_PERMISSION_SDK &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_LOCAL_NETWORK) != PackageManager.PERMISSION_GRANTED
        ) {
            permissionLauncher.launch(Manifest.permission.ACCESS_LOCAL_NETWORK)
        } else {
            viewModel.connect()
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .navigationBarsPadding()
            .testTag("addserver_manual_screen"),
    ) {
        TopBar(title = "Enter an address", onBack = onBack)

        Column(Modifier.padding(horizontal = Spacing.s18), verticalArrangement = Arrangement.spacedBy(Spacing.s18)) {
            val error = state.error
            if (state.phase == AddServerUiState.Phase.Failed && error != null) {
                ErrorCard(message = error, detail = state.errorDetail, testTag = "addserver_error_card")
            } else if (permissionDenied) {
                ErrorCard(
                    message = "Regolith needs local network access to reach a server on your Wi-Fi. Allow it in Settings › Apps › Regolith › Permissions.",
                    testTag = "addserver_permission_card",
                )
            }

            RegolithTextField(
                value = state.address,
                onValueChange = viewModel::onAddressChange,
                label = "Address",
                placeholder = "smb://192.168.1.24",
                isError = state.addressError != null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                testTag = "addserver_address_field",
            )
            state.addressError?.let { Text(it, style = TextStyles.metadata, color = colors.accent) }
            RegolithTextField(
                value = state.username,
                onValueChange = viewModel::onUsernameChange,
                label = "Username",
                placeholder = "Guest",
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Next, autoCorrectEnabled = false),
                testTag = "addserver_username_field",
            )
            RegolithTextField(
                value = state.password,
                onValueChange = viewModel::onPasswordChange,
                label = "Password",
                placeholder = "Optional",
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { showPassword = !showPassword }, modifier = Modifier.testTag("addserver_password_toggle")) {
                        Icon(
                            painterResource(if (showPassword) LucideR.drawable.lucide_ic_eye_off else LucideR.drawable.lucide_ic_eye),
                            contentDescription = if (showPassword) "Hide password" else "Show password",
                            tint = colors.metadata,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                },
                testTag = "addserver_password_field",
            )
            RegolithSwitch(
                label = "Save credentials",
                checked = state.saveCredentials,
                onCheckedChange = viewModel::onSaveCredentialsChange,
                enabled = !state.isGuest,
                testTag = "addserver_save_credentials_switch",
            )

            Spacer(Modifier.height(Spacing.s8))
            PrimaryButton(
                text = if (state.phase == AddServerUiState.Phase.Failed) "Try again" else "Connect",
                onClick = ::connectWithPermission,
                enabled = state.canConnect,
                testTag = "addserver_connect_button",
                modifier = Modifier.fillMaxWidth(),
            )
            if (state.phase == AddServerUiState.Phase.Failed && !state.isGuest) {
                SecondaryButton(
                    text = "Connect as guest",
                    onClick = viewModel::connectAsGuest,
                    testTag = "addserver_guest_button",
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.height(Spacing.s40))
        }
    }
}

/** Android 17 (API 37) is where the local-network permission is enforced. */
private const val LOCAL_NETWORK_PERMISSION_SDK = 37

/** "CONNECTING smb://…" with a red arc on a hairline track, and Cancel. */
@Composable
private fun ConnectingContent(address: String, onCancel: () -> Unit, modifier: Modifier = Modifier) {
    val colors = RegolithTheme.colors
    Box(modifier.fillMaxSize().navigationBarsPadding().testTag("addserver_connecting_screen"), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Spacing.s18)) {
            CircularProgressIndicator(color = colors.accent, trackColor = colors.hairline, strokeWidth = 3.dp, modifier = Modifier.size(56.dp))
            DisplayText("Connecting")
            Text(address, style = TextStyles.metadata, color = colors.metadata)
            Spacer(Modifier.height(Spacing.s8))
            TertiaryButton(text = "Cancel", onClick = onCancel, testTag = "addserver_cancel_button")
        }
    }
}
