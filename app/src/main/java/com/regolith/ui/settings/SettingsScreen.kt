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
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.ui.graphics.Color
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
import com.regolith.ui.adaptive.LocalWindowShape
import com.regolith.ui.components.PrimaryButton
import com.regolith.ui.components.RegolithSwitch
import com.regolith.ui.components.SecondaryButton
import com.regolith.ui.components.SettingsRowHeight
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
 * Settings tab (design section 11): SHARES as a card of rows (an 8dp
 * status dot, the server name in Michroma 12, a "SHOWING" tag, the free
 * space on the right, scan and disconnect at the end), then "Scan all"
 * and "Add a share"; PLAYBACK as a card of switch rows; MEDIA for the
 * artwork cache. Every row in every card sits at the same
 * [SettingsRowHeight] floor, note or no note, so a bare label does not
 * read as a shorter row than its neighbours. The disconnect confirm
 * names what survives ("Keep it", not "Cancel").
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
                // Every share carries its own scan and disconnect. They used
                // to be one pair of buttons under the whole card, which meant
                // Disconnect always took the first server in the list — with
                // two NAS boxes connected there was no way to remove the
                // second one at all.
                SurfaceCard(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = Spacing.s12)) {
                    state.servers.forEach { row ->
                        Row(Modifier.fillMaxWidth().defaultMinSize(minHeight = SettingsRowHeight).padding(vertical = Spacing.s12).testTag(row.testTag), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(8.dp).background(if (row.reachable) colors.ink else colors.metadata, PillShape))
                            Spacer(Modifier.width(Spacing.s12))
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.s2)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.s8)) {
                                    Text(row.name, style = TextStyles.settingLabel, overflow = TextOverflow.Ellipsis, color = if (row.reachable) colors.ink else colors.body, maxLines = 1)
                                    if (row.showing) Tag("Showing")
                                }
                                Text(row.status, style = TextStyles.settingMeta, color = colors.metadata, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Spacer(Modifier.width(Spacing.s8))
                            RowAction(
                                icon = R.drawable.rg_ic_refresh,
                                // The status line already says "Scanning · N files",
                                // so a running scan greys its own button rather than
                                // needing a spinner of its own.
                                contentDescription = "Scan ${row.name}",
                                enabled = !row.scanning,
                                onClick = { viewModel.scan(row.serverId) },
                                testTag = "settings_scan_${row.serverId}",
                            )
                            RowAction(
                                icon = R.drawable.rg_ic_trash,
                                contentDescription = "Disconnect ${row.name}",
                                tint = colors.accent,
                                onClick = { viewModel.askDisconnect(row) },
                                testTag = "settings_disconnect_${row.serverId}",
                            )
                        }
                    }
                }
                if (state.servers.size > 1) {
                    SecondaryButton(
                        text = "Scan all", onClick = viewModel::scanAll, compact = true,
                        enabled = state.servers.none { it.scanning }, testTag = "settings_scan_all_button",
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                // Adding a share is what this screen is FOR when nothing is
                // connected, and the only constructive action here otherwise.
                PrimaryButton(
                    text = "Add a share", onClick = onAddServer, compact = true,
                    testTag = "settings_add_share_button", modifier = Modifier.fillMaxWidth(),
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(Spacing.s12)) {
                Eyebrow("Playback", muted = true)
                SurfaceCard(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = Spacing.s12)) {
                    RegolithSwitch(label = "Hardware decoding", checked = state.hardwareDecoding, onCheckedChange = viewModel::setHardwareDecoding, testTag = "settings_hardware_decoding_switch")
                    RegolithSwitch(
                        label = "Scrub thumbnails", note = "Preview frame while you drag the timeline.",
                        checked = state.scrubThumbnails, onCheckedChange = viewModel::setScrubThumbnails, testTag = "settings_scrub_thumbnails_switch",
                    )
                    RegolithSwitch(
                        label = "Keep playing", note = "When a file ends, start the next one in the folder.",
                        checked = state.autoplayNext, onCheckedChange = viewModel::setAutoplayNext, testTag = "settings_autoplay_next_switch",
                    )
                    // Indented under its parent and greyed when it is off: you
                    // cannot skip a question you are not being asked. Disabled
                    // rather than hidden, so the option is visible before you
                    // turn the parent on and the list never changes shape.
                    NestedRow {
                        RegolithSwitch(
                            label = "Don't ask first", note = "Skip the ten-second Up next card and go straight in.",
                            checked = state.autoplayImmediately, onCheckedChange = viewModel::setAutoplayImmediately,
                            enabled = state.autoplayNext, testTag = "settings_autoplay_immediately_switch",
                        )
                    }
                }
            }

            // Wide windows only: on a phone the pill is the bottom bar and
            // there is no side space to reclaim, so the row would toggle
            // something the owner of a phone can never see.
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.s12)) {
                Eyebrow("Display", muted = true)
                SurfaceCard(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = Spacing.s12)) {
                    // Wide windows only: on a phone the pill is the bottom bar
                    // and there is no side space to reclaim, so the row would
                    // toggle something a phone can never show.
                    if (LocalWindowShape.current.wide) {
                        RegolithSwitch(
                            label = "Auto-hide the rail", note = "Slides the navigation rail away three seconds after you stop touching the screen.",
                            checked = state.autoHideRail, onCheckedChange = viewModel::setAutoHideRail, testTag = "settings_auto_hide_rail_switch",
                        )
                    }
                    // The one switch in the app that is about battery rather
                    // than taste, so the note says what it costs instead of
                    // only what it does.
                    RegolithSwitch(
                        label = "Ambient light",
                        note = "Colour from the picture spills onto the screen around it, and follows the film as it plays. Costs a little battery.",
                        checked = state.ambientLight, onCheckedChange = viewModel::setAmbientLight, testTag = "settings_ambient_light_switch",
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(Spacing.s12)) {
                Eyebrow("Media", muted = true)
                SurfaceCard(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = Spacing.s12)) {
                    Row(Modifier.defaultMinSize(minHeight = SettingsRowHeight).padding(vertical = Spacing.s12), verticalAlignment = Alignment.CenterVertically) {
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
                    SurfaceCard(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = Spacing.s12)) {
                        Row(Modifier.defaultMinSize(minHeight = SettingsRowHeight).padding(vertical = Spacing.s12), verticalAlignment = Alignment.CenterVertically) {
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

/**
 * A settings row that depends on the one above it: indented, with a hairline
 * down its start edge standing in for the bracket a nested list would draw.
 * The child itself carries the disabled state; this is only the geometry.
 */
@Composable
private fun NestedRow(content: @Composable () -> Unit) {
    val colors = RegolithTheme.colors
    Row(Modifier.height(IntrinsicSize.Min)) {
        Box(Modifier.width(1.dp).fillMaxHeight().padding(vertical = Spacing.s8).background(colors.hairline))
        Box(Modifier.padding(start = Spacing.s12).weight(1f)) { content() }
    }
}

/**
 * A 40dp icon button at the end of a settings row. Smaller and quieter than
 * [IconCircleButton]: a row can carry two of these without the card turning
 * into a button bar.
 */
@Composable
private fun RowAction(
    icon: Int,
    contentDescription: String,
    onClick: () -> Unit,
    testTag: String,
    enabled: Boolean = true,
    tint: Color? = null,
) {
    val colors = RegolithTheme.colors
    Box(
        Modifier.size(40.dp).clip(PillShape)
            .clickable(interactionSource = null, indication = null, enabled = enabled, onClick = onClick)
            .testTag(testTag),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painterResource(icon),
            contentDescription = contentDescription,
            tint = if (!enabled) colors.disabledInk else tint ?: colors.body,
            modifier = Modifier.size(17.scaledDp()),
        )
    }
}
