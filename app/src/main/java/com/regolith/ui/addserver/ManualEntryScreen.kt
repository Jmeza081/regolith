package com.regolith.ui.addserver

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
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
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.composables.icons.lucide.R as LucideR
import com.regolith.R
import com.regolith.ui.components.DisplayText
import com.regolith.ui.components.ErrorCard
import com.regolith.ui.components.NoticeCard
import com.regolith.ui.components.PrimaryButton
import com.regolith.ui.components.RegolithTextField
import com.regolith.ui.components.SecondaryButton
import com.regolith.ui.components.StrataLoader
import com.regolith.ui.components.SwitchControl
import com.regolith.ui.components.TertiaryButton
import com.regolith.ui.components.TopBar
import com.regolith.ui.theme.RegolithTheme
import com.regolith.ui.theme.Spacing
import com.regolith.ui.theme.TextStyles
import com.regolith.ui.theme.designSp

/**
 * "Enter an address" (design section 03): three fields with the label
 * inside the card, the password's eye toggle inside its card, "Save
 * credentials" as a 48dp row with the switch, and Connect. Connecting and
 * Sign-in-failed are states of this screen so the typed values survive a
 * failure: the failed state retitles the bar with the server's name, puts
 * the message on a notice card, and offers "Try again" and "Connect as
 * guest".
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
    val failed = state.phase == AddServerUiState.Phase.Failed

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
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().navigationBarsPadding().testTag("addserver_manual_screen"),
    ) {
        TopBar(title = if (failed) state.address.removePrefix("smb://").substringBefore('/').ifBlank { "Sign-in failed" } else "Enter an address", onBack = onBack)

        Column(Modifier.padding(horizontal = Spacing.s18), verticalArrangement = Arrangement.spacedBy(Spacing.s12)) {
            val error = state.error
            if (failed && error != null) {
                NoticeCard(message = error, detail = state.errorDetail, testTag = "addserver_error_card")
            } else if (permissionDenied) {
                ErrorCard(
                    message = "Regolith needs local network access to reach a server on your Wi-Fi. Allow it in Settings › Apps › Regolith › Permissions.",
                    testTag = "addserver_permission_card",
                )
            }

            if (!failed) {
                RegolithTextField(
                    value = state.address,
                    onValueChange = viewModel::onAddressChange,
                    label = "Address",
                    placeholder = "smb://192.168.1.24",
                    isError = state.addressError != null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                    testTag = "addserver_address_field",
                )
                state.addressError?.let { Text(it, style = TextStyles.meta, color = colors.accent) }
            }
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
                isError = failed && !state.isGuest,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    Box(
                        Modifier.size(40.dp).clickable(interactionSource = null, indication = null) { showPassword = !showPassword }.testTag("addserver_password_toggle"),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            painterResource(if (showPassword) LucideR.drawable.lucide_ic_eye_off else R.drawable.rg_ic_eye),
                            contentDescription = if (showPassword) "Hide password" else "Show password",
                            tint = if (showPassword) colors.inkSoft else colors.body,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                },
                testTag = "addserver_password_field",
            )
            if (!failed) {
                Row(Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Save credentials", style = TextStyles.settingLabel.copy(lineHeight = 20.designSp()), color = colors.inkSoft, modifier = Modifier.weight(1f))
                    SwitchControl(checked = state.saveCredentials, onCheckedChange = viewModel::onSaveCredentialsChange, testTag = "addserver_save_credentials_switch")
                }
            }

            PrimaryButton(
                text = if (failed) "Try again" else "Connect",
                onClick = ::connectWithPermission,
                enabled = state.canConnect,
                testTag = "addserver_connect_button",
                modifier = Modifier.fillMaxWidth(),
            )
            if (failed && !state.isGuest) {
                SecondaryButton(text = "Connect as guest", onClick = viewModel::connectAsGuest, testTag = "addserver_guest_button", modifier = Modifier.fillMaxWidth())
            }
            Spacer(Modifier.height(Spacing.s40))
        }
    }
}

/** Android 17 (API 37) is where the local-network permission is enforced. */
private const val LOCAL_NETWORK_PERMISSION_SDK = 37

/**
 * "CONNECTING smb://…" (design section 03): a red arc on a #1F1F1F track
 * spinning at 1.1 s with the server mark counter-rotated so it never
 * tilts, the title in Michroma 16, the address at 13px, and Cancel.
 */
@Composable
private fun ConnectingContent(address: String, onCancel: () -> Unit, modifier: Modifier = Modifier) {
    val colors = RegolithTheme.colors
    Box(modifier.fillMaxSize().navigationBarsPadding().testTag("addserver_connecting_screen"), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Spacing.s30)) {
            // The wedge, where a ring with a server glyph inside it used to
            // turn. The glyph does not come along: it sat INSIDE the ring, and
            // there is no inside to a wedge -- it would land on the bands. The
            // screen says "Connecting" under this and names the address, so
            // the glyph was saying a third time what the words already say.
            StrataLoader(height = 64.dp, testTag = "addserver_connecting_loader")
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Spacing.s8)) {
                DisplayText("Connecting", style = TextStyles.dialogTitle.copy(fontSize = 16.designSp(), lineHeight = 22.4.designSp()))
                Text(address, style = TextStyles.body.copy(fontSize = 13.designSp(), lineHeight = 13.designSp()), color = colors.metadata)
            }
            TertiaryButton(text = "Cancel", onClick = onCancel, testTag = "addserver_cancel_button")
        }
    }
}
