package com.regolith.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.regolith.R
import com.regolith.ui.components.ArtworkImage
import com.regolith.ui.components.DisplayText
import com.regolith.ui.components.Eyebrow
import com.regolith.ui.components.PrimaryButton
import com.regolith.ui.components.ResumeCard
import com.regolith.ui.components.SurfaceCard
import com.regolith.ui.components.TertiaryButton
import com.regolith.ui.components.TopBar
import com.regolith.ui.components.TopBarAction
import com.regolith.ui.components.UnwatchedDot
import com.regolith.ui.theme.PillShape
import com.regolith.ui.theme.RegolithTheme
import com.regolith.ui.theme.Spacing
import com.regolith.ui.theme.TextStyles
import com.regolith.ui.theme.TileShape
import com.regolith.ui.util.formatBytes
import com.regolith.ui.util.formatRemaining

/**
 * Home tab (design section 04): resume first, then what arrived. Five
 * states: no source server, resume, nothing started (no resume row at
 * all: its absence is the message), and the two halves of a pull to
 * refresh, which never blanks a screen that already has content.
 *
 * Measurements from the frames: sections 18dp apart, eyebrows at 18dp
 * gutters, resume cards 186dp wide, Newly added posters 112dp wide with a
 * light-haloed dot and no caption, 112dp of clearance under the last row
 * for the nav pill.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onAddServer: () -> Unit,
    onSearch: () -> Unit,
    onOpenTitle: (fileId: Long) -> Unit,
    onPlay: (fileId: Long, startMs: Long) -> Unit,
    onOpenDevice: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = RegolithTheme.colors
    val pullState = rememberPullToRefreshState()
    val refreshing = state.refreshLine != null
    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = viewModel::refresh,
        state = pullState,
        modifier = modifier.fillMaxSize().testTag("home_screen"),
        indicator = {
            // The design's refresh line: a 30dp ring with the arrow, then "Release to refresh" /
            // one line of live status, never a Material spinner.
            RefreshLine(
                fraction = pullState.distanceFraction,
                refreshing = refreshing,
                status = state.refreshLine,
                modifier = Modifier.align(Alignment.TopCenter),
            )
        },
    ) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            TopBar(
                title = "Home",
                actions = if (state.hasSource) listOf(TopBarAction(R.drawable.rg_ic_search, "Search", "home_search_button", onSearch)) else emptyList(),
            )
            if (!state.loaded) return@Column
            if (!state.hasSource) {
                NoSourceServer(onAddServer)
                return@Column
            }
            if (refreshing) Spacer(Modifier.height(30.dp + Spacing.s18))

            Column(verticalArrangement = Arrangement.spacedBy(Spacing.s18)) {
                if (state.resume.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.s8)) {
                        Row(Modifier.padding(horizontal = Spacing.s18), verticalAlignment = Alignment.Bottom) {
                            Eyebrow("Continue watching", Modifier.weight(1f))
                            Text("All", style = TextStyles.link, color = colors.ink, modifier = Modifier.testTag("home_resume_all"))
                        }
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = Spacing.s18),
                            horizontalArrangement = Arrangement.spacedBy(Spacing.s8),
                            modifier = Modifier.testTag("home_resume_row"),
                        ) {
                            items(state.resume, key = { it.fileId }) { item ->
                                ResumeCard(
                                    artwork = item.artwork,
                                    title = item.name,
                                    meta = item.meta,
                                    timeLeft = formatRemaining(item.positionMs, item.durationMs),
                                    progress = item.fraction,
                                    onClick = { onPlay(item.fileId, item.positionMs) },
                                    testTag = item.testTag,
                                    modifier = Modifier.width(186.dp),
                                )
                            }
                        }
                    }
                }

                if (state.newlyAdded.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.s8)) {
                        Eyebrow("Newly added", Modifier.padding(horizontal = Spacing.s18))
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = Spacing.s18),
                            horizontalArrangement = Arrangement.spacedBy(Spacing.s8),
                            modifier = Modifier.testTag("home_new_row"),
                        ) {
                            items(state.newlyAdded, key = { it.fileId }) { item ->
                                Box(
                                    Modifier
                                        .width(if (state.resume.isEmpty()) 96.dp else 112.dp)
                                        .aspectRatio(2f / 3f)
                                        .clip(TileShape)
                                        .background(colors.surface)
                                        .clickable(interactionSource = null, indication = null) { onOpenTitle(item.fileId) }
                                        .testTag(item.testTag),
                                ) {
                                    ArtworkImage(item.artwork, Modifier.fillMaxSize(), fallbackLabel = item.name)
                                    if (item.unwatched) UnwatchedDot(Modifier.align(Alignment.TopEnd).padding(4.dp), lightHalo = true, size = 8.dp)
                                }
                            }
                        }
                    }
                } else if (state.neverScanned && !refreshing) {
                    Column(Modifier.padding(horizontal = Spacing.s18), verticalArrangement = Arrangement.spacedBy(Spacing.s8)) {
                        Eyebrow("Newly added")
                        Text("Nothing yet. Scan the share and what it holds shows up here.", style = TextStyles.body, color = colors.body)
                        PrimaryButton(text = "Scan now", onClick = viewModel::refresh, testTag = "home_scan_button")
                    }
                }

                if (state.downloadsReady > 0) {
                    Column(Modifier.padding(horizontal = Spacing.s18), verticalArrangement = Arrangement.spacedBy(Spacing.s8)) {
                        Eyebrow("On this device")
                        SurfaceCard(
                            modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenDevice).testTag("home_on_device_row"),
                            contentPadding = PaddingValues(Spacing.s12),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(painterResource(R.drawable.rg_ic_download), contentDescription = null, tint = colors.body, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(Spacing.s12))
                                Text(
                                    "${state.downloadsReady} download" + (if (state.downloadsReady == 1) " ready" else "s ready"),
                                    style = TextStyles.rowLabelSmall, color = colors.ink, modifier = Modifier.weight(1f),
                                )
                                Text(formatBytes(state.downloadsBytes), style = TextStyles.meta, color = colors.metadata)
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(112.dp))
        }
    }
}

/**
 * The refresh line (design: "Pull to refresh" / "Refreshing"): a 30dp
 * ring with a 22% hairline holding the arrow, and a tracked 10px label.
 * Released, the arrow becomes one line of live status.
 */
@Composable
private fun RefreshLine(fraction: Float, refreshing: Boolean, status: String?, modifier: Modifier = Modifier) {
    val colors = RegolithTheme.colors
    val visible = refreshing || fraction > 0.05f
    if (!visible) return
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.s8),
        modifier = modifier.padding(top = Spacing.s8).alpha(if (refreshing) 1f else fraction.coerceIn(0f, 1f)).testTag("home_refresh_line"),
    ) {
        Box(Modifier.size(30.dp).border(1.dp, colors.onMediaBorder, PillShape), contentAlignment = Alignment.Center) {
            Icon(painterResource(if (refreshing) R.drawable.rg_ic_collapse else R.drawable.rg_ic_arrow_up), contentDescription = null, tint = colors.ink, modifier = Modifier.size(15.dp))
        }
        Text(
            (status ?: if (fraction >= 1f) "Release to refresh" else "Pull to refresh").uppercase(),
            style = TextStyles.refreshLabel, color = colors.navIdle,
        )
    }
}

/**
 * No source server (design section 04): the 44dp server mark in a 13dp
 * box, a two-line Michroma 17 title, body copy, the one red action and
 * "Enter an address" as plain text, all centred in the space above the pill.
 */
@Composable
private fun NoSourceServer(onAddServer: () -> Unit) {
    val colors = RegolithTheme.colors
    Column(
        Modifier.fillMaxWidth().padding(horizontal = Spacing.s18).padding(top = 80.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.s18),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Spacing.s12)) {
            Box(Modifier.size(44.dp).border(1.dp, colors.raised, androidx.compose.foundation.shape.RoundedCornerShape(13.dp)), contentAlignment = Alignment.Center) {
                Icon(painterResource(R.drawable.rg_ic_server), contentDescription = null, tint = colors.body, modifier = Modifier.size(21.dp))
            }
            DisplayText("No source\nserver", style = TextStyles.emptyTitle, textAlign = TextAlign.Center)
            Text(
                "Regolith plays what is already on your own network. Point it at a share and everything on it shows up here.",
                style = TextStyles.body, color = colors.body, textAlign = TextAlign.Center,
            )
        }
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.s8)) {
            PrimaryButton(
                text = "Add source server", onClick = onAddServer, testTag = "home_add_server_button",
                leadingIcon = painterResource(R.drawable.rg_ic_search), modifier = Modifier.fillMaxWidth(),
            )
            TertiaryButton(text = "Enter an address", onClick = onAddServer, testTag = "home_enter_address_button", modifier = Modifier.fillMaxWidth())
        }
    }
}
