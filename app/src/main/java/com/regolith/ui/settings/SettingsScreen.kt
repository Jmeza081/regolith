package com.regolith.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.composables.icons.lucide.R as LucideR
import com.regolith.ui.components.DestructiveButton
import com.regolith.ui.components.DisplayText
import com.regolith.ui.components.Eyebrow
import com.regolith.ui.components.ListRow
import com.regolith.ui.components.RegolithSwitch
import com.regolith.ui.components.RowTrailing
import com.regolith.ui.components.SecondaryButton
import com.regolith.ui.components.SurfaceCard
import com.regolith.ui.components.TertiaryButton
import com.regolith.ui.components.TopBar
import com.regolith.ui.theme.CardShape
import com.regolith.ui.theme.RegolithTheme
import com.regolith.ui.theme.Spacing
import com.regolith.ui.theme.TextStyles
import com.regolith.ui.util.formatBytes

/**
 * Settings tab (design section 11): shares with their state, add / scan
 * all / disconnect, playback preferences, and the media cache. The
 * disconnect confirm names what survives ("Keep it", not "Cancel").
 */
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onAddServer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = RegolithTheme.colors
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).testTag("settings_screen")) {
        TopBar(title = state.title)
        Column(Modifier.padding(horizontal = Spacing.s18)) {
            Eyebrow("Shares · ${state.servers.size}")
            Spacer(Modifier.height(Spacing.s8))
            state.servers.forEach { row ->
                ListRow(
                    title = row.name,
                    meta = row.status,
                    icon = LucideR.drawable.lucide_ic_server,
                    trailing = RowTrailing.None,
                    onClick = {},
                    testTag = row.testTag,
                )
            }
            Spacer(Modifier.height(Spacing.s12))
            Row(verticalAlignment = Alignment.CenterVertically) {
                SecondaryButton(text = "Add a share", onClick = onAddServer, testTag = "settings_add_share_button")
                Spacer(Modifier.padding(Spacing.s4))
                SecondaryButton(text = "Scan all", onClick = viewModel::scanAll, enabled = state.servers.isNotEmpty() && state.servers.none { it.scanning }, testTag = "settings_scan_all_button")
                Spacer(Modifier.weight(1f))
                if (state.servers.isNotEmpty()) {
                    DestructiveButton(text = "Disconnect", onClick = { viewModel.askDisconnect(state.servers.first()) }, testTag = "settings_disconnect_button")
                }
            }
            Spacer(Modifier.height(Spacing.s40))

            Eyebrow("Playback")
            Spacer(Modifier.height(Spacing.s8))
            RegolithSwitch(label = "Hardware decoding", checked = state.hardwareDecoding, onCheckedChange = viewModel::setHardwareDecoding, testTag = "settings_hardware_decoding_switch")
            Text("Direct play, lowest battery cost. Software decoding plays what the chip cannot.", style = TextStyles.metadata, color = colors.metadata, modifier = Modifier.padding(top = Spacing.s4))
            Spacer(Modifier.height(Spacing.s18))
            RegolithSwitch(label = "Scrub thumbnails", checked = state.scrubThumbnails, onCheckedChange = viewModel::setScrubThumbnails, testTag = "settings_scrub_thumbnails_switch")
            Text("Preview frame while you drag the timeline.", style = TextStyles.metadata, color = colors.metadata, modifier = Modifier.padding(top = Spacing.s4))
            Spacer(Modifier.height(Spacing.s40))

            Eyebrow("Media")
            Spacer(Modifier.height(Spacing.s8))
            SurfaceCard(modifier = Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Artwork cache", style = TextStyles.rowLabel, color = colors.ink)
                        Text(
                            if (state.artworkCount == 0) "Nothing cached yet" else "${state.artworkCount} images · ${formatBytes(state.artworkBytes)}",
                            style = TextStyles.metadata,
                            color = colors.metadata,
                            modifier = Modifier.testTag("settings_artwork_meta"),
                        )
                    }
                    SecondaryButton(
                        text = "Clear",
                        onClick = viewModel::clearArtwork,
                        enabled = !state.clearing && state.artworkCount > 0,
                        testTag = "settings_clear_artwork_button",
                    )
                }
                Text(
                    "Posters and frames are read from the share and kept here. Clearing forces a fresh read; nothing on the share changes.",
                    style = TextStyles.metadata,
                    color = colors.metadata,
                    modifier = Modifier.padding(top = Spacing.s12),
                )
            }
            Spacer(Modifier.height(120.dp))
        }
    }

    state.confirmDisconnect?.let { row ->
        AlertDialog(
            onDismissRequest = { viewModel.askDisconnect(null) },
            containerColor = colors.surface,
            shape = CardShape,
            title = { DisplayText("Disconnect ${row.name}?") },
            text = {
                Text(
                    "The media list is removed from this device. Nothing on the share is touched, and you can add it back with the same address.",
                    style = TextStyles.body, color = colors.body,
                )
            },
            confirmButton = { DestructiveButton(text = "Disconnect", onClick = { viewModel.disconnect(row) }, testTag = "settings_disconnect_confirm_button") },
            dismissButton = { TertiaryButton(text = "Keep it", onClick = { viewModel.askDisconnect(null) }, testTag = "settings_disconnect_keep_button") },
            modifier = Modifier.testTag("settings_disconnect_dialog"),
        )
    }
}
