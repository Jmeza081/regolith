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
import androidx.compose.runtime.LaunchedEffect
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
import com.regolith.ui.components.ConfirmDialog
import com.regolith.ui.components.DestructiveButton
import com.regolith.ui.components.DisplayText
import com.regolith.ui.components.Eyebrow
import com.regolith.ui.adaptive.LocalWindowShape
import com.regolith.ui.components.PrimaryButton
import com.regolith.ui.components.RowAction
import com.regolith.ui.components.RegolithSwitch
import com.regolith.ui.components.SecondaryButton
import com.regolith.ui.components.SettingsRowHeight
import androidx.activity.compose.LocalActivity
import androidx.compose.ui.draw.alpha
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.regolith.domain.security.BiometricAvailability
import com.regolith.domain.security.LockAfter
import com.regolith.ui.components.Segment
import com.regolith.ui.components.SegmentedTabs
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
    /**
     * Where the Downloads row leads. Downloads deliberately have no
     * destination of their own (design section 05: "Downloads live here
     * rather than in a tab of their own"), so this goes to Library › On
     * this device.
     */
    onOpenDownloads: () -> Unit = {},
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val activity = LocalActivity.current
    // Enrolling a fingerprint happens in the system settings, so the answer
    // can change while this screen is in the background.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { viewModel.refreshBiometrics() }
    }
    val colors = RegolithTheme.colors
    // Preparing artwork runs as a foreground job with a notification, and
    // Android 13+ only shows it once notifications are allowed. Asked on the
    // tap rather than on arrival: this screen has plenty of other reasons to
    // be open, and a prompt out of nowhere is a prompt people say no to.
    val context = androidx.compose.ui.platform.LocalContext.current
    val askNotifications = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) {}
    val prepareArtwork = {
        if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            askNotifications.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        viewModel.prepareArtwork()
    }
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
            // Downloads, between Playback and Display: Media below is about
            // the artwork cache, which is a different kind of storage
            // question. This is the "a dot appeared, what is it" answer.
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.s12)) {
                Eyebrow("Downloads", muted = true)
                SurfaceCard(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = Spacing.s12)) {
                    Row(
                        Modifier
                            .defaultMinSize(minHeight = SettingsRowHeight)
                            .padding(vertical = Spacing.s12)
                            .testTag("settings_downloads_row"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // The same 8dp status dot the share rows use, so the two
                        // read as one vocabulary rather than two.
                        Box(
                            Modifier
                                .size(8.dp)
                                .background(
                                    when (state.downloads.dotState) {
                                        DownloadDot.ARRIVING -> colors.ink
                                        DownloadDot.FAILED -> colors.accent
                                        DownloadDot.IDLE -> colors.metadata
                                    },
                                    PillShape,
                                )
                                .testTag("settings_downloads_dot"),
                        )
                        Spacer(Modifier.width(Spacing.s12))
                        Column(Modifier.weight(1f)) {
                            Text("Downloads", style = TextStyles.settingLabel, color = colors.ink)
                            Text(
                                state.downloads.meta,
                                style = TextStyles.settingMeta, color = colors.metadata,
                                modifier = Modifier.testTag("settings_downloads_meta"),
                            )
                        }
                        Spacer(Modifier.width(Spacing.s12))
                        if (state.downloads.running) {
                            SecondaryButton(
                                text = "Stop", onClick = viewModel::stopDownloads,
                                compact = true, testTag = "settings_downloads_stop_button",
                            )
                        } else {
                            SecondaryButton(
                                text = "Open", onClick = onOpenDownloads,
                                compact = true, testTag = "settings_downloads_open_button",
                            )
                        }
                    }
                    // The artwork walk's own bar, reused verbatim: two background
                    // jobs reporting themselves two different ways would read as
                    // two features.
                    state.downloads.fraction?.let { fraction ->
                        if (state.downloads.running) {
                            Box(Modifier.fillMaxWidth().padding(bottom = Spacing.s12).height(3.dp).background(colors.hairline, PillShape).testTag("settings_downloads_progress")) {
                                Box(Modifier.fillMaxWidth(fraction).height(3.dp).background(colors.ink, PillShape))
                            }
                        }
                    }
                }
            }
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
                    // The background walk, said here as well as in the
                    // notification: the shade is where you find out about it
                    // by accident, and this is where you come to look.
                    Row(Modifier.defaultMinSize(minHeight = SettingsRowHeight).padding(vertical = Spacing.s12), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Prepare artwork", style = TextStyles.settingLabel, color = colors.ink)
                            Text(
                                when {
                                    state.prefetch.running && state.prefetch.total > 0 ->
                                        "Working · ${state.prefetch.done} of ${state.prefetch.total}"
                                    state.prefetch.running -> "Working out what needs a picture"
                                    else -> "Makes every poster and thumbnail up front, so the library is not still drawing itself while you scroll."
                                },
                                style = TextStyles.settingMeta, color = colors.metadata,
                                modifier = Modifier.testTag("settings_prefetch_meta"),
                            )
                        }
                        Spacer(Modifier.width(Spacing.s12))
                        SecondaryButton(
                            text = if (state.prefetch.running) "Stop" else "Prepare",
                            onClick = { if (state.prefetch.running) viewModel.stopArtwork() else prepareArtwork() },
                            compact = true, testTag = "settings_prefetch_button",
                        )
                    }
                    if (state.prefetch.running && state.prefetch.total > 0) {
                        Box(Modifier.fillMaxWidth().padding(bottom = Spacing.s12).height(3.dp).background(colors.hairline, PillShape).testTag("settings_prefetch_progress")) {
                            Box(Modifier.fillMaxWidth(state.prefetch.fraction).height(3.dp).background(colors.ink, PillShape))
                        }
                    }
                }
            }
            if (BuildConfig.DEMO_LIBRARY) {
                // Privacy (P11): the app lock. The switch is only usable when
            // the device has something to check against; the note says which
            // way it is short so the answer is not "it just does nothing".
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.s12)) {
                Eyebrow("Privacy", muted = true)
                SurfaceCard(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = Spacing.s12)) {
                    RegolithSwitch(
                        label = "Lock Regolith",
                        note = when {
                            state.biometrics == BiometricAvailability.NONE_ENROLLED ->
                                "Set up a fingerprint, face unlock or a screen lock on this phone first."
                            state.biometrics == BiometricAvailability.NO_HARDWARE ->
                                "This device has no fingerprint reader, face unlock or screen lock."
                            state.biometrics == BiometricAvailability.UNAVAILABLE && !state.appLock ->
                                "The fingerprint reader is not answering just now."
                            else -> "Ask for a fingerprint, face or screen lock before showing the library."
                        },
                        checked = state.appLock,
                        onCheckedChange = { want -> activity?.let { viewModel.setAppLock(it, want) } },
                        // Already on stays switchable off even if the reader has since gone quiet.
                        enabled = state.appLock || state.biometrics.canEnable,
                        testTag = "settings_app_lock_switch",
                    )
                    NestedRow {
                        Column(
                            Modifier.padding(vertical = Spacing.s12).alpha(if (state.appLock) 1f else DISABLED_ALPHA),
                            verticalArrangement = Arrangement.spacedBy(Spacing.s8),
                        ) {
                            Text("Ask again", style = TextStyles.settingLabel, color = colors.ink)
                            Text(
                                "After you have left Regolith for this long.",
                                style = TextStyles.settingMeta, color = colors.metadata,
                            )
                            SegmentedTabs(
                                segments = LockAfter.entries.map { Segment(it.label, "settings_app_lock_after_${it.name.lowercase()}") },
                                selected = LockAfter.entries.indexOf(state.appLockAfter),
                                onSelect = { if (state.appLock) viewModel.setAppLockAfter(LockAfter.entries[it]) },
                            )
                        }
                    }
                }
            }

            // The chapters the user wrote (P9), and the one way to clear
            // them all. Per-film revert lives on the player's Chapters
            // sheet; this is for starting over.
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.s12)) {
                Eyebrow("Chapters", muted = true)
                SurfaceCard(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = Spacing.s12)) {
                    Row(Modifier.defaultMinSize(minHeight = SettingsRowHeight).padding(vertical = Spacing.s12), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Your chapters", style = TextStyles.settingLabel, color = colors.ink)
                            Text(
                                state.userChapters.let { c ->
                                    if (c.isEmpty) "None on this phone yet · mark them from the player's Chapters sheet"
                                    else (if (c.chapters == 1) "1 chapter" else "${c.chapters} chapters") + " on " + (if (c.files == 1) "1 video" else "${c.files} videos") + " kept on this phone"
                                },
                                style = TextStyles.settingMeta, color = colors.metadata, modifier = Modifier.testTag("settings_chapters_meta"),
                            )
                        }
                        Spacer(Modifier.width(Spacing.s12))
                        SecondaryButton(
                            text = "Clear", onClick = { viewModel.askClearChapters(true) }, compact = true,
                            enabled = !state.userChapters.isEmpty, testTag = "settings_clear_chapters_button",
                        )
                    }
                    // P10: one switch per share. Off keeps that share's chapters
                    // on the phone; the files already there are left alone.
                    state.shareWrites.forEach { row ->
                        RegolithSwitch(
                            label = "Write to ${row.label}",
                            note = if (row.enabled) "Chapters are saved as a text file beside each film, so other devices and tools can read them." else "Chapters for this share stay on this phone.",
                            checked = row.enabled, onCheckedChange = { viewModel.setShareWriteChapters(row.shareId, it) }, testTag = row.testTag,
                        )
                    }
                }
            }

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

    if (state.confirmClearChapters) {
        val c = state.userChapters
        ConfirmDialog(
            title = "Clear chapters?",
            body = "Clears the " + (if (c.chapters == 1) "1 chapter" else "${c.chapters} chapters") + " kept on this phone. Nothing on the share is touched: films with a chapter file there get theirs back at the next scan; the rest go back to their own markers or the even split.",
            confirmLabel = "Clear chapters", keepLabel = "Keep them",
            onConfirm = viewModel::clearChapters, onKeep = { viewModel.askClearChapters(false) },
            testTag = "settings_clear_chapters",
        )
    }
    state.confirmDisconnect?.let { row ->
        ConfirmDialog(
            title = "Disconnect ${row.name}?",
            body = "The media list is removed from this device. Nothing on the share is touched, and you can add it back with the same address.",
            confirmLabel = "Disconnect", keepLabel = "Keep it",
            onConfirm = { viewModel.disconnect(row) }, onKeep = { viewModel.askDisconnect(null) },
            testTag = "settings_disconnect",
        )
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

/** A nested control whose parent switch is off: readable, plainly not in play. */
private const val DISABLED_ALPHA = 0.38f
