package com.regolith.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.regolith.BuildConfig
import com.regolith.R
import com.regolith.ui.components.LocalNavPillInsets
import com.regolith.ui.components.DestructiveButton
import com.regolith.ui.components.DisplayText
import com.regolith.ui.components.Eyebrow
import com.regolith.ui.components.RegolithSwitch
import com.regolith.ui.components.SecondaryButton
import com.regolith.ui.components.SurfaceCard
import com.regolith.ui.components.Tag
import com.regolith.ui.components.TopBar
import com.regolith.ui.theme.DialogShape
import com.regolith.ui.theme.PillShape
import com.regolith.ui.theme.RegolithTheme
import com.regolith.ui.theme.Spacing
import com.regolith.ui.theme.TextStyles
import com.regolith.ui.util.formatBytes
import com.regolith.ui.theme.scaledDp

/**
 * Settings tab (design section 11): SHARES as a card of 48dp rows (an
 * 8dp status dot, the server name in Michroma 12, a "SHOWING" tag, the
 * free space on the right, "Add a share" as the last row), then "Scan
 * all" and "Disconnect" side by side at 42dp; PLAYBACK as a card of 56dp
 * switch rows; MEDIA for the artwork cache. The disconnect confirm names
 * what survives ("Keep it", not "Cancel").
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
        Column(Modifier.padding(horizontal = Spacing.s18), verticalArrangement = Arrangement.spacedBy(Spacing.s18)) {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.s12)) {
                Eyebrow("Shares · ${state.servers.size}", muted = true)
                SurfaceCard(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = Spacing.s12)) {
                    state.servers.forEach { row ->
                        Row(Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp).testTag(row.testTag), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(8.dp).background(if (row.reachable) colors.ink else colors.metadata, PillShape))
                            Spacer(Modifier.width(Spacing.s12))
                            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.s8)) {
                                Text(row.name, style = TextStyles.settingLabel, overflow = TextOverflow.Ellipsis, color = if (row.reachable) colors.ink else colors.body, maxLines = 1)
                                if (row.showing) Tag("Showing")
                            }
                            Text(row.status, style = TextStyles.meta, color = colors.metadata, maxLines = 1)
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp)
                            .clickable(interactionSource = null, indication = null, onClick = onAddServer)
                            .testTag("settings_add_share_button"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Spacer(Modifier.width(8.dp + Spacing.s12))
                        Text("Add a share", style = TextStyles.rowLabelSmall, color = colors.ink, modifier = Modifier.weight(1f))
                        Icon(painterResource(R.drawable.rg_ic_chevron_right), contentDescription = null, tint = colors.metadata, modifier = Modifier.size(15.dp))
                    }
                }
                if (state.servers.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s8)) {
                        SecondaryButton(
                            text = "Scan all", onClick = viewModel::scanAll, compact = true,
                            enabled = state.servers.none { it.scanning }, testTag = "settings_scan_all_button", modifier = Modifier.weight(1f),
                        )
                        DestructiveButton(
                            text = "Disconnect", onClick = { viewModel.askDisconnect(state.servers.first()) }, compact = true,
                            testTag = "settings_disconnect_button", modifier = Modifier.weight(1f),
                        )
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(Spacing.s12)) {
                Eyebrow("Playback", muted = true)
                SurfaceCard(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = Spacing.s12)) {
                    RegolithSwitch(label = "Hardware decoding", checked = state.hardwareDecoding, onCheckedChange = viewModel::setHardwareDecoding, testTag = "settings_hardware_decoding_switch")
                    RegolithSwitch(
                        label = "Scrub thumbnails", note = "Preview frame while you drag the timeline.",
                        checked = state.scrubThumbnails, onCheckedChange = viewModel::setScrubThumbnails, testTag = "settings_scrub_thumbnails_switch",
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(Spacing.s12)) {
                Eyebrow("Media", muted = true)
                SurfaceCard(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(Spacing.s12)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Artwork cache", style = TextStyles.settingLabel, color = colors.ink)
                            Text(
                                if (state.artworkCount == 0) "Nothing cached yet" else "${state.artworkCount} images · ${formatBytes(state.artworkBytes)}",
                                style = TextStyles.settingMeta, color = colors.metadata, modifier = Modifier.testTag("settings_artwork_meta"),
                            )
                        }
                        SecondaryButton(
                            text = "Clear", onClick = viewModel::clearArtwork, compact = true,
                            enabled = !state.clearing && state.artworkCount > 0, testTag = "settings_clear_artwork_button",
                        )
                    }
                }
            }
            if (BuildConfig.DEMO_LIBRARY) {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.s12)) {
                    Eyebrow("Demo", muted = true)
                    SurfaceCard(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(Spacing.s12)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Demo library", style = TextStyles.settingLabel, color = colors.ink)
                                Text(
                                    when {
                                        state.demoWorking && state.demoInstalled -> "Removing…"
                                        state.demoWorking -> "Writing files…"
                                        state.demoInstalled -> "A pretend NAS with 18 titles · ${formatBytes(state.demoBytes)}"
                                        else -> "A pretend NAS you can browse and play with no network"
                                    },
                                    style = TextStyles.settingMeta, color = colors.metadata,
                                    modifier = Modifier.testTag("settings_demo_meta"),
                                )
                            }
                            SecondaryButton(
                                text = if (state.demoInstalled) "Remove" else "Load",
                                onClick = viewModel::toggleDemoLibrary, compact = true,
                                enabled = !state.demoWorking, testTag = "settings_demo_button",
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(LocalNavPillInsets.current.calculateBottomPadding() - Spacing.s18))
        }
    }

    state.confirmDisconnect?.let { row ->
        DisconnectDialog(name = row.name, onConfirm = { viewModel.disconnect(row) }, onKeep = { viewModel.askDisconnect(null) })
    }
}

/**
 * "DISCONNECT TOWER?" (design: the one destructive confirm): a #0F0F0F
 * card at 20dp corners with a #2E2E2E hairline over a 72% scrim, the
 * title in Michroma 15, body copy, red Disconnect over a frosted Keep it.
 */
@Composable
private fun DisconnectDialog(name: String, onConfirm: () -> Unit, onKeep: () -> Unit) {
    val colors = RegolithTheme.colors
    Dialog(onDismissRequest = onKeep) {
        Column(
            Modifier.fillMaxWidth().background(colors.surface, DialogShape).border(1.dp, colors.raised, DialogShape).padding(Spacing.s18).testTag("settings_disconnect_dialog"),
            verticalArrangement = Arrangement.spacedBy(Spacing.s12),
        ) {
            DisplayText("Disconnect $name?", style = TextStyles.dialogTitle)
            Text(
                "The media list is removed from this device. Nothing on the share is touched, and you can add it back with the same address.",
                style = TextStyles.body, color = colors.body,
            )
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.s8)) {
                DestructiveButton(text = "Disconnect", onClick = onConfirm, testTag = "settings_disconnect_confirm_button", modifier = Modifier.fillMaxWidth().height(48.scaledDp()))
                SecondaryButton(text = "Keep it", onClick = onKeep, testTag = "settings_disconnect_keep_button", modifier = Modifier.fillMaxWidth())
            }
        }
    }
}
