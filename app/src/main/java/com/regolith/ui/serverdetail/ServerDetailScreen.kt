package com.regolith.ui.serverdetail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.composables.icons.lucide.R as LucideR
import com.regolith.R
import com.regolith.domain.model.ServerAddress
import com.regolith.ui.components.ConfirmDialog
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.regolith.ui.theme.DialogMaxWidth
import com.regolith.ui.components.DisplayText
import com.regolith.ui.components.PrimaryButton
import com.regolith.ui.components.RegolithTextField
import com.regolith.ui.components.Eyebrow
import com.regolith.ui.components.LocalNavPillInsets
import com.regolith.ui.components.PromptDialog
import com.regolith.ui.components.SecondaryButton
import com.regolith.ui.components.SettingsRowHeight
import com.regolith.ui.components.SurfaceCard
import com.regolith.ui.components.TopBar
import com.regolith.ui.settings.disconnectBody
import com.regolith.ui.theme.PillShape
import com.regolith.ui.theme.RegolithTheme
import com.regolith.ui.theme.Spacing
import com.regolith.ui.theme.TextStyles
import com.regolith.ui.util.formatWhen

/** Longest a server name may be, as on the Settings rename. */
private const val MAX_SERVER_NAME = 24
private const val MAX_LABEL = 18

/**
 * One source server's page: what it is called, the ways to reach it, and
 * what its library is doing.
 *
 * **Why this screen exists.** Everything about a server used to live as
 * three buttons crammed into a Settings row, and one thing — which folders
 * of a share are in the library — had no home at all outside the Add
 * Server flow, so it could be chosen once and never changed. Giving a
 * server a page of its own is what makes room for the addresses, and it
 * pays for itself by giving the folder choice somewhere to be.
 */
@Composable
fun ServerDetailScreen(
    viewModel: ServerDetailViewModel,
    onBack: () -> Unit,
    onChooseShares: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = RegolithTheme.colors

    Column(modifier.fillMaxSize().testTag("server_detail_screen")) {
        TopBar(title = state.name.ifBlank { "Server" }, onBack = onBack)

        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.s18)
                .padding(bottom = LocalNavPillInsets.current.calculateBottomPadding()),
            verticalArrangement = Arrangement.spacedBy(Spacing.s18),
        ) {
            SurfaceCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = Spacing.s12)) {
                Row(
                    Modifier
                        .defaultMinSize(minHeight = SettingsRowHeight)
                        .clickable(interactionSource = null, indication = null) { viewModel.askRename(true) }
                        .padding(vertical = Spacing.s12)
                        .testTag("server_name_row"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Name", style = TextStyles.settingLabel, color = colors.ink)
                        Text(state.name, style = TextStyles.settingMeta, color = colors.metadata)
                    }
                    Text("Rename", style = TextStyles.buttonTertiary, color = colors.ink)
                }
            }

            // --- How to reach it
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.s12)) {
                Eyebrow("How to reach it", muted = true)
                SurfaceCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = Spacing.s12)) {
                    // Only once there is a choice to make. With one address
                    // "Automatic" and "that one" are the same thing, and a
                    // control whose two settings do nothing different is
                    // worse than no control.
                    if (state.rows.size > 1) {
                        AutomaticRow(selected = state.automatic, onSelect = viewModel::useFastest)
                        Divider()
                    }
                    state.rows.forEachIndexed { index, row ->
                        if (index > 0) Divider()
                        AddressRowView(
                            row = row,
                            automatic = state.automatic,
                            multiple = state.rows.size > 1,
                            onChoose = { viewModel.chooseAddress(row) },
                            onLabel = { viewModel.askLabel(row.address) },
                            onRemove = { viewModel.askRemove(row.address) },
                        )
                    }
                    Divider()
                    Row(
                        Modifier
                            .defaultMinSize(minHeight = SettingsRowHeight)
                            .clickable(interactionSource = null, indication = null) { viewModel.askAddAddress(true) }
                            .padding(vertical = Spacing.s12)
                            .testTag("server_add_address"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(painterResource(LucideR.drawable.lucide_ic_plus), contentDescription = null, tint = colors.ink, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(Spacing.s12))
                        Text("Add another address", style = TextStyles.settingLabel, color = colors.ink)
                    }
                }
                Text(
                    addressHint(state.mode, state.rows.firstOrNull { it.inUse }?.address, state.rows.size),
                    style = TextStyles.settingMeta,
                    color = colors.metadata,
                    modifier = Modifier.testTag("server_address_hint"),
                )
                if (!state.automatic) {
                    SecondaryButton(
                        text = "Use whichever is fastest",
                        onClick = viewModel::useFastest,
                        modifier = Modifier.fillMaxWidth(),
                        testTag = "server_use_fastest",
                    )
                }
            }

            // --- Its library
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.s12)) {
                Eyebrow("Its library", muted = true)
                if (state.holdingBack) SlowLinkNotice(state, onAnyway = viewModel::overrideSlowLink)
                SurfaceCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = Spacing.s12)) {
                    Row(
                        Modifier
                            .defaultMinSize(minHeight = SettingsRowHeight)
                            .clickable(interactionSource = null, indication = null, onClick = onChooseShares)
                            .padding(vertical = Spacing.s12)
                            .testTag("server_folders_row"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Shares and folders", style = TextStyles.settingLabel, color = colors.ink)
                            Text(
                                if (state.shareCount == 0) "Nothing chosen yet"
                                else "${state.enabledShareCount} of ${state.shareCount} in the library",
                                style = TextStyles.settingMeta, color = colors.metadata,
                            )
                        }
                        Icon(painterResource(R.drawable.rg_ic_chevron_right), contentDescription = null, tint = colors.metadata, modifier = Modifier.size(16.dp))
                    }

                    Divider()

                    Row(
                        Modifier.defaultMinSize(minHeight = SettingsRowHeight).padding(vertical = Spacing.s12).testTag("server_scan_row"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Scan", style = TextStyles.settingLabel, color = colors.ink)
                            Text(scanMeta(state), style = TextStyles.settingMeta, color = colors.metadata)
                        }
                        Spacer(Modifier.width(Spacing.s12))
                        SecondaryButton(
                            text = when {
                                state.scanning -> "Stop"
                                state.holdingBack -> "Anyway"
                                else -> "Scan now"
                            },
                            onClick = {
                                when {
                                    state.scanning -> viewModel.stopScan()
                                    // An explicit tap is consent: it runs, and it
                                    // lifts the hold for the rest of this visit so
                                    // the artwork behind it is not asked again.
                                    else -> { viewModel.overrideSlowLink(); viewModel.scanNow() }
                                }
                            },
                            compact = true,
                            testTag = "server_scan_button",
                        )
                    }

                    Divider()

                    Column(Modifier.padding(vertical = Spacing.s12).testTag("server_artwork_row"), verticalArrangement = Arrangement.spacedBy(Spacing.s8)) {
                        Row(Modifier.defaultMinSize(minHeight = 44.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Artwork", style = TextStyles.settingLabel, color = colors.ink)
                                Text(artworkMeta(state), style = TextStyles.settingMeta, color = colors.metadata)
                            }
                            Spacer(Modifier.width(Spacing.s12))
                            SecondaryButton(
                                text = when {
                                    state.artworkRunning -> "Stop"
                                    state.holdingBack -> "Anyway"
                                    else -> "Prepare"
                                },
                                onClick = {
                                    if (state.artworkRunning) viewModel.stopArtwork()
                                    else { viewModel.overrideSlowLink(); viewModel.prepareArtwork() }
                                },
                                compact = true,
                                testTag = "server_artwork_button",
                            )
                        }
                        if (state.artworkRunning && state.artworkTotal > 0) {
                            Box(Modifier.fillMaxWidth().height(3.dp).clip(PillShape).background(colors.hairline)) {
                                Box(
                                    Modifier
                                        .fillMaxWidth((state.artworkDone.toFloat() / state.artworkTotal).coerceIn(0f, 1f))
                                        .height(3.dp)
                                        .background(colors.accent),
                                )
                            }
                        }
                    }
                }
            }

            SurfaceCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = Spacing.s12)) {
                Row(
                    Modifier
                        .defaultMinSize(minHeight = SettingsRowHeight)
                        .clickable(interactionSource = null, indication = null) { viewModel.askDisconnect(true) }
                        .padding(vertical = Spacing.s12)
                        .testTag("server_disconnect_row"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Disconnect", style = TextStyles.settingLabel, color = colors.accent)
                        Text("The media list goes; downloads stay on this phone", style = TextStyles.settingMeta, color = colors.metadata)
                    }
                }
            }

            state.message?.let { message ->
                Text(
                    message,
                    style = TextStyles.settingMeta,
                    color = colors.accent,
                    modifier = Modifier
                        .clickable(interactionSource = null, indication = null) { viewModel.dismissMessage() }
                        .testTag("server_message"),
                )
            }

            Spacer(Modifier.height(Spacing.s18))
        }
    }

    if (state.renaming) {
        PromptDialog(
            title = "Name this server",
            label = "Name",
            initialValue = state.name,
            confirmLabel = "Save",
            onConfirm = viewModel::rename,
            onCancel = { viewModel.askRename(false) },
            testTag = "server_rename",
            maxLength = MAX_SERVER_NAME,
        )
    }
    if (state.addingAddress) {
        AddAddressDialog(
            error = state.message,
            onConfirm = { typed, label -> viewModel.addAddress(typed, label) },
            onCancel = { viewModel.askAddAddress(false) },
        )
    }
    state.labelling?.let { address ->
        PromptDialog(
            title = "Name this address",
            label = "Name",
            initialValue = address.label,
            placeholder = "Home",
            note = address.address,
            confirmLabel = "Save",
            onConfirm = { viewModel.setLabel(address, it) },
            onCancel = { viewModel.askLabel(null) },
            testTag = "server_label",
            maxLength = MAX_LABEL,
        )
    }
    state.confirmRemove?.let { address ->
        ConfirmDialog(
            title = "Forget ${address.title}?",
            body = "Regolith stops trying this way in. The library, the artwork and where you got to in each film are untouched, and the other addresses still work.",
            confirmLabel = "Forget it", keepLabel = "Keep it",
            onConfirm = { viewModel.removeAddress(address) },
            onKeep = { viewModel.askRemove(null) },
            testTag = "server_remove_address",
        )
    }
    if (state.confirmDisconnect) {
        ConfirmDialog(
            title = "Disconnect ${state.name}?",
            body = disconnectBody(state.confirmDisconnectKeeps),
            confirmLabel = "Disconnect", keepLabel = "Keep it",
            onConfirm = { viewModel.disconnect(onBack) },
            onKeep = { viewModel.askDisconnect(false) },
            testTag = "server_disconnect",
        )
    }
}

/** A hairline between rows inside one card, matching Settings. */
@Composable
private fun Divider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(RegolithTheme.colors.hairline))
}

/**
 * One way in.
 *
 * The dot on the left says what is TRUE (this is the address in use); the
 * row's tap says what you WANT (pin this one, or unpin it). Keeping those
 * apart is the point — an automatic choice that looked like a setting
 * would be read as one, and then changing networks would look like the app
 * forgetting what you told it.
 */
@Composable
private fun AddressRowView(
    row: AddressRow,
    automatic: Boolean,
    multiple: Boolean,
    onChoose: () -> Unit,
    onLabel: () -> Unit,
    onRemove: () -> Unit,
) {
    val colors = RegolithTheme.colors
    Row(
        Modifier
            .defaultMinSize(minHeight = SettingsRowHeight)
            .clickable(interactionSource = null, indication = null, onClick = onChoose)
            .padding(vertical = Spacing.s12)
            .testTag(row.testTag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Filled red = the choice you made. A bare white dot on the title
        // row = the address actually in use. They are different claims and
        // are drawn differently on purpose.
        Box(
            Modifier.size(18.dp).clip(PillShape).background(if (row.pinned) colors.accent else colors.raised),
            contentAlignment = Alignment.Center,
        ) {
            if (row.pinned) Box(Modifier.size(6.dp).clip(PillShape).background(colors.inkSoft))
        }
        Spacer(Modifier.width(Spacing.s12))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(row.address.title, style = TextStyles.settingLabel, color = colors.ink)
                if (row.inUse) {
                    Spacer(Modifier.width(Spacing.s8))
                    Box(Modifier.size(6.dp).clip(PillShape).background(colors.ink))
                    Spacer(Modifier.width(Spacing.s8))
                    Text(
                        if (row.pinned) "PINNED" else "IN USE",
                        style = TextStyles.eyebrow, color = colors.metadata,
                        modifier = Modifier.testTag("${row.testTag}_state"),
                    )
                }
            }
            val detail = row.address.detail
            if (detail.isNotEmpty()) Text(detail, style = TextStyles.settingMeta, color = colors.metadata)
        }
        Spacer(Modifier.width(Spacing.s8))
        Text(
            "Name",
            style = TextStyles.buttonTertiary,
            color = colors.ink,
            modifier = Modifier
                .clickable(interactionSource = null, indication = null, onClick = onLabel)
                .padding(horizontal = Spacing.s8, vertical = Spacing.s12)
                .testTag("${row.testTag}_label"),
        )
        Text(
            "Forget",
            style = TextStyles.buttonTertiary,
            color = colors.metadata,
            modifier = Modifier
                .clickable(interactionSource = null, indication = null, onClick = onRemove)
                .padding(start = Spacing.s8, top = Spacing.s12, bottom = Spacing.s12)
                .testTag("${row.testTag}_forget"),
        )
    }
}

private fun scanMeta(state: ServerDetailUiState): String = when {
    state.scanning -> "Reading · ${"%,d".format(state.scanFilesFound)} files so far"
    state.unreachable -> "Out of reach"
    state.lastScanAtMs != null -> "${"%,d".format(state.fileCount)} files · read ${formatWhen(state.lastScanAtMs)}"
    else -> "Never read"
}

private fun artworkMeta(state: ServerDetailUiState): String = when {
    state.artworkRunning && state.artworkTotal > 0 -> "${"%,d".format(state.artworkDone)} of ${"%,d".format(state.artworkTotal)}"
    state.artworkRunning -> "Working out what needs a picture"
    else -> "Posters and thumbnails, made up front"
}

/**
 * "Add another address": the address, and optionally what to call it.
 *
 * Two fields, which is why this is not a [PromptDialog] — and the name is
 * genuinely optional, because a MagicDNS name is unreadable at this width
 * but a bare IP on the home network needs no explaining.
 */
@Composable
private fun AddAddressDialog(
    error: String?,
    onConfirm: (typed: String, label: String) -> Unit,
    onCancel: () -> Unit,
) {
    val colors = RegolithTheme.colors
    var typed by rememberSaveable { mutableStateOf("") }
    var label by rememberSaveable { mutableStateOf("") }
    Dialog(onDismissRequest = onCancel, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxWidth().padding(horizontal = Spacing.s18), contentAlignment = Alignment.Center) {
            SurfaceCard(Modifier.widthIn(max = DialogMaxWidth).testTag("server_add_address_dialog")) {
                DisplayText("Add an address", style = TextStyles.dialogTitle)
                Spacer(Modifier.height(Spacing.s12))
                Text(
                    "Another way to reach the same machine — a LAN address for when you are home, a VPN name for when you are not.",
                    style = TextStyles.body, color = colors.body,
                )
                Spacer(Modifier.height(Spacing.s18))
                RegolithTextField(
                    value = typed,
                    onValueChange = { typed = it },
                    label = "Address",
                    placeholder = "192.168.4.82",
                    testTag = "server_add_address_field",
                )
                Spacer(Modifier.height(Spacing.s12))
                RegolithTextField(
                    value = label,
                    onValueChange = { if (it.length <= MAX_LABEL) label = it },
                    label = "Name (optional)",
                    placeholder = "Home",
                    testTag = "server_add_label_field",
                )
                error?.let {
                    Spacer(Modifier.height(Spacing.s12))
                    Text(it, style = TextStyles.settingMeta, color = colors.accent, modifier = Modifier.testTag("server_add_address_error"))
                }
                Spacer(Modifier.height(Spacing.s18))
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s8)) {
                    SecondaryButton(text = "Cancel", onClick = onCancel, modifier = Modifier.weight(1f), testTag = "server_add_address_cancel")
                    PrimaryButton(
                        text = "Add",
                        onClick = { onConfirm(typed, label) },
                        enabled = typed.isNotBlank(),
                        modifier = Modifier.weight(1f),
                        testTag = "server_add_address_confirm",
                    )
                }
            }
        }
    }
}

/**
 * "Automatic": let the fastest answer decide, every time.
 *
 * It sits above the addresses rather than beside them because it is not
 * one of them — it is the rule that picks between them, and drawing it as
 * a fourth address would invite the question of what happens when you
 * choose "Automatic" and an address at the same time.
 */
@Composable
private fun AutomaticRow(selected: Boolean, onSelect: () -> Unit) {
    val colors = RegolithTheme.colors
    Row(
        Modifier
            .defaultMinSize(minHeight = SettingsRowHeight)
            .clickable(interactionSource = null, indication = null, onClick = onSelect)
            .padding(vertical = Spacing.s12)
            .testTag("server_address_automatic"),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            Modifier.size(18.dp).clip(PillShape).background(if (selected) colors.accent else colors.raised),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) Box(Modifier.size(6.dp).clip(PillShape).background(colors.inkSoft))
        }
        Spacer(Modifier.width(Spacing.s12))
        Column(Modifier.weight(1f)) {
            Text("Automatic", style = TextStyles.settingLabel, color = colors.ink)
            Text(
                "Tries every address at once and uses whichever answers first.",
                style = TextStyles.settingMeta,
                color = colors.metadata,
            )
        }
    }
}

/**
 * Why the library is not reading itself right now.
 *
 * Shown only when a FASTER way in exists and cannot be reached from here,
 * which is the one case where waiting actually buys something — a library
 * at the end of a slow line is not far away, it is just what that setup
 * costs, and saying so on every screen would be noise.
 *
 * It states the cost in wall-clock rather than in multiples, because
 * "3x slower" is a fact about the link and "about six hours" is a fact
 * about the decision in front of you.
 */
@Composable
private fun SlowLinkNotice(state: ServerDetailUiState, onAnyway: () -> Unit) {
    val colors = RegolithTheme.colors
    SurfaceCard(Modifier.fillMaxWidth().testTag("server_slow_link")) {
        Text("Waiting for a faster way in", style = TextStyles.settingLabel, color = colors.ink)
        Spacer(Modifier.height(Spacing.s8))
        state.slowLinkLine?.let {
            Text(it, style = TextStyles.settingMeta, color = colors.metadata)
            Spacer(Modifier.height(Spacing.s4))
        }
        Text(
            slowLinkBody(state.fileCount, state.slowdown),
            style = TextStyles.body,
            color = colors.body,
        )
        Spacer(Modifier.height(Spacing.s12))
        Text(
            "Your library, its artwork and where you got to in each film are already on this phone. Playing and downloading work from here.",
            style = TextStyles.settingMeta,
            color = colors.metadata,
        )
        Spacer(Modifier.height(Spacing.s18))
        SecondaryButton(
            text = "Read it anyway",
            onClick = onAnyway,
            modifier = Modifier.fillMaxWidth(),
            testTag = "server_slow_link_anyway",
        )
    }
}
