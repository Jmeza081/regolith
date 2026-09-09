package com.regolith.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.composables.icons.lucide.R as LucideR
import com.regolith.domain.artwork.ArtworkKind
import com.regolith.ui.components.DisplayText
import com.regolith.ui.components.Eyebrow
import com.regolith.ui.components.ListRow
import com.regolith.ui.components.MediaTile
import com.regolith.ui.components.PrimaryButton
import com.regolith.ui.components.RowTrailing
import com.regolith.ui.components.TertiaryButton
import com.regolith.ui.components.TopBar
import com.regolith.ui.theme.RegolithTheme
import com.regolith.ui.theme.Spacing
import com.regolith.ui.theme.TextStyles
import com.regolith.ui.util.formatRemaining

/**
 * Home tab (design section 04): resume first, then what arrived. Five
 * states: no source server, resume, nothing started (no resume row at
 * all: its absence is the message), and the two halves of a pull to
 * refresh, which never blanks a screen that already has content.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onAddServer: () -> Unit,
    onBrowse: () -> Unit,
    onOpenTitle: (fileId: Long) -> Unit,
    onPlay: (fileId: Long, startMs: Long) -> Unit,
    onOpenDevice: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = RegolithTheme.colors
    PullToRefreshBox(
        isRefreshing = state.refreshLine != null,
        onRefresh = viewModel::refresh,
        modifier = modifier.fillMaxSize().testTag("home_screen"),
    ) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            TopBar(title = "Home")
            if (!state.loaded) return@Column
            if (!state.hasSource) {
                Column(Modifier.padding(horizontal = Spacing.s18)) {
                    Spacer(Modifier.height(Spacing.s30))
                    DisplayText("No source\nserver")
                    Spacer(Modifier.height(Spacing.s12))
                    Text(
                        "Regolith plays what is already on your own network. Point it at a share and everything on it shows up here.",
                        style = TextStyles.body,
                        color = colors.body,
                    )
                    Spacer(Modifier.height(Spacing.s18))
                    PrimaryButton(text = "Add source server", onClick = onAddServer, testTag = "home_add_server_button", modifier = Modifier.fillMaxWidth())
                    TertiaryButton(text = "Enter an address", onClick = onAddServer, testTag = "home_enter_address_button", modifier = Modifier.fillMaxWidth())
                }
                return@Column
            }

            state.refreshLine?.let {
                Text(it, style = TextStyles.metadata, color = colors.metadata, modifier = Modifier.padding(horizontal = Spacing.s18).testTag("home_refresh_line"))
                Spacer(Modifier.height(Spacing.s12))
            }

            if (state.resume.isNotEmpty()) {
                Eyebrow("Continue watching", Modifier.padding(horizontal = Spacing.s18))
                Spacer(Modifier.height(Spacing.s8))
                LazyRow(
                    contentPadding = PaddingValues(horizontal = Spacing.s18),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.s12),
                    modifier = Modifier.testTag("home_resume_row"),
                ) {
                    items(state.resume, key = { it.fileId }) { item ->
                        MediaTile(
                            artwork = item.artwork,
                            kind = ArtworkKind.THUMB,
                            title = item.name,
                            meta = item.meta,
                            chip = formatRemaining(item.positionMs, item.durationMs),
                            progress = item.fraction,
                            placeholderLabel = item.name,
                            onClick = { onPlay(item.fileId, item.positionMs) },
                            testTag = item.testTag,
                            modifier = Modifier.width(220.dp),
                        )
                    }
                }
                Spacer(Modifier.height(Spacing.s30))
            }

            if (state.newlyAdded.isNotEmpty()) {
                Eyebrow("Newly added", Modifier.padding(horizontal = Spacing.s18))
                Spacer(Modifier.height(Spacing.s8))
                LazyRow(
                    contentPadding = PaddingValues(horizontal = Spacing.s18),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.s8),
                    modifier = Modifier.testTag("home_new_row"),
                ) {
                    items(state.newlyAdded, key = { it.fileId }) { item ->
                        MediaTile(
                            artwork = item.artwork,
                            kind = ArtworkKind.POSTER,
                            title = item.name,
                            meta = item.meta,
                            placeholderLabel = item.name,
                            onClick = { onOpenTitle(item.fileId) },
                            testTag = item.testTag,
                            modifier = Modifier.width(110.dp),
                        )
                    }
                }
                Spacer(Modifier.height(Spacing.s30))
            } else if (state.neverScanned && state.refreshLine == null) {
                Column(Modifier.padding(horizontal = Spacing.s18)) {
                    Eyebrow("Newly added")
                    Spacer(Modifier.height(Spacing.s8))
                    Text("Nothing yet. Scan the share and what it holds shows up here.", style = TextStyles.body, color = colors.body)
                    Spacer(Modifier.height(Spacing.s12))
                    PrimaryButton(text = "Scan now", onClick = viewModel::refresh, testTag = "home_scan_button")
                    Spacer(Modifier.height(Spacing.s30))
                }
            }

            if (state.downloadsReady > 0) {
                Column(Modifier.padding(horizontal = Spacing.s18)) {
                    Eyebrow("On this device")
                    Spacer(Modifier.height(Spacing.s8))
                    ListRow(
                        title = "${state.downloadsReady} download" + (if (state.downloadsReady == 1) " ready" else "s ready"),
                        meta = com.regolith.ui.util.formatBytes(state.downloadsBytes),
                        icon = LucideR.drawable.lucide_ic_hard_drive,
                        onClick = onOpenDevice,
                        testTag = "home_on_device_row",
                    )
                    Spacer(Modifier.height(Spacing.s30))
                }
            }

            Column(Modifier.padding(horizontal = Spacing.s18)) {
                Eyebrow("Source servers")
                Spacer(Modifier.height(Spacing.s8))
                state.serverNames.forEach { name ->
                    ListRow(title = name, icon = LucideR.drawable.lucide_ic_server, onClick = onBrowse, trailing = RowTrailing.Chevron, testTag = "home_server_$name")
                }
                Row {
                    TertiaryButton(text = "Add another", onClick = onAddServer, testTag = "home_add_server_button")
                }
                Spacer(Modifier.height(120.dp))
            }
        }
    }
}
