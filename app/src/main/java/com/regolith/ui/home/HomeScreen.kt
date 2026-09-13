package com.regolith.ui.home

import androidx.compose.foundation.background
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.pullToRefresh
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.regolith.R
import com.regolith.ui.adaptive.LocalWindowShape
import com.regolith.ui.components.LocalNavPillInsets
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
import com.regolith.ui.theme.scaledDp

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
    onOpenContinueWatching: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = RegolithTheme.colors
    val pullState = rememberPullToRefreshState()
    val refreshing = state.refreshLine != null
    // The content follows the finger at 60% of the pull, which is what makes
    // the gesture feel like it is moving the page rather than arming a
    // hidden switch. It springs back on release, and drops away once the
    // refresh line takes over the space.
    val thresholdPx = with(LocalDensity.current) { PULL_THRESHOLD.toPx() }
    val dragOffset = pullState.distanceFraction.coerceAtMost(MAX_PULL) * thresholdPx * PULL_FOLLOW
    val settling by animateFloatAsState(if (refreshing) 0f else dragOffset, label = "pullSettle")
    val pullOffset = if (refreshing) settling else dragOffset
    val wide = LocalWindowShape.current.wide

    // BoxWithConstraints: the wide layout sizes the resume cards from the
    // real width (three edge to edge), which a plain Box cannot read.
    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .pullToRefresh(
                isRefreshing = refreshing,
                state = pullState,
                // Material's 80dp triggered on the smallest flick down; this
                // asks for a deliberate pull instead.
                threshold = PULL_THRESHOLD,
                onRefresh = viewModel::refresh,
            )
            .testTag("home_screen"),
    ) {
        // Wide: three cards fill the row. Phone: the design's card-and-a-half peek.
        // Read here, at the BoxWithConstraints level, since maxWidth is not in scope inside the Column.
        val resumeWidth = if (wide) (maxWidth - Spacing.s18 * 2 - Spacing.s8 * 2) / 3 else RESUME_CARD_WIDTH
        // The design's refresh line: a 30dp ring with the arrow, then "Release to refresh" /
        // one line of live status, never a Material spinner.
        RefreshLine(
            fraction = pullState.distanceFraction,
            refreshing = refreshing,
            status = state.refreshLine,
            modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().graphicsLayer { translationY = pullOffset },
        )
        Column(
            Modifier.fillMaxSize()
                .graphicsLayer { translationY = pullOffset }
                .verticalScroll(rememberScrollState()),
        ) {
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
                        SectionHeader("Continue watching", onAll = onOpenContinueWatching, allTestTag = "home_resume_all")
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
                                    modifier = Modifier.width(resumeWidth),
                                )
                            }
                        }
                    }
                }

                if (state.newlyAdded.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.s8)) {
                        Eyebrow("Newly added", Modifier.padding(horizontal = Spacing.s18))
                        if (wide) {
                            // The wall: rows of six posters, edge to edge. Home is a
                            // vertical scroll, so this is plain rows rather than a
                            // LazyVerticalGrid (which would need its own height).
                            // The tag stays the same so the QA flows find it either way.
                            Column(Modifier.padding(horizontal = Spacing.s18).testTag("home_new_row"), verticalArrangement = Arrangement.spacedBy(Spacing.s8)) {
                                state.newlyAdded.chunked(WALL_COLUMNS).forEach { row ->
                                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s8)) {
                                        row.forEach { item -> NewPoster(item, onOpenTitle, Modifier.weight(1f)) }
                                        repeat(WALL_COLUMNS - row.size) { Spacer(Modifier.weight(1f)) }
                                    }
                                }
                            }
                        } else {
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = Spacing.s18),
                                horizontalArrangement = Arrangement.spacedBy(Spacing.s8),
                                modifier = Modifier.testTag("home_new_row"),
                            ) {
                                items(state.newlyAdded, key = { it.fileId }) { item ->
                                    NewPoster(item, onOpenTitle, Modifier.width(if (state.resume.isEmpty()) 96.scaledDp() else NEW_POSTER_WIDTH))
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

                // What is already here, as the films themselves rather than a
                // card counting them. Same tile as Newly added; the size the
                // card used to carry moves into the header, where it is still
                // the answer to "how much of the phone is this using".
                if (state.onDevice.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.s8)) {
                        SectionHeader(
                            title = "On this device",
                            onAll = onOpenDevice,
                            allTestTag = "home_device_all",
                            meta = formatBytes(state.downloadsBytes),
                        )
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = Spacing.s18),
                            horizontalArrangement = Arrangement.spacedBy(Spacing.s8),
                            modifier = Modifier.testTag("home_on_device_row"),
                        ) {
                            items(state.onDevice, key = { it.fileId }) { item ->
                                NewPoster(item, onOpenTitle, Modifier.width(NEW_POSTER_WIDTH))
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(LocalNavPillInsets.current.calculateBottomPadding()))
        }
    }
}

/**
 * The resume card. The design drew 186px on a 320px frame — a card and a
 * half in view; at 411dp that had become two and a half, and the row read
 * as small tiles rather than "the thing you were watching".
 */
private val RESUME_CARD_WIDTH = 256.dp

/** Posters across on the wide Home wall (the inner-display frame). */
private const val WALL_COLUMNS = 6

/**
 * A row's title, with the way to see all of it on the right and an
 * optional quiet fact between them. Two rows on Home end in "All", and a
 * third nearly did before this existed.
 */
@Composable
private fun SectionHeader(
    title: String,
    onAll: () -> Unit,
    allTestTag: String,
    meta: String? = null,
) {
    val colors = RegolithTheme.colors
    Row(
        Modifier.padding(horizontal = Spacing.s18),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(Spacing.s8),
    ) {
        Eyebrow(title, Modifier.weight(1f))
        meta?.let { Text(it, style = TextStyles.meta, color = colors.metadata) }
        Text(
            "All", style = TextStyles.link, color = colors.ink,
            modifier = Modifier
                .clickable(interactionSource = null, indication = null, onClick = onAll)
                .testTag(allTestTag),
        )
    }
}

/** One "Newly added" poster: 2:3 art with the unwatched dot. The row and the wall draw the same one. */
@Composable
private fun NewPoster(item: NewItem, onOpenTitle: (fileId: Long) -> Unit, modifier: Modifier = Modifier) {
    val colors = RegolithTheme.colors
    Box(
        modifier
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

/**
 * "Newly added". The design's 112px was 35% of a 320px frame and had
 * become 27% of the screen; [com.regolith.ui.theme.SIZE_SCALE] puts it
 * back. The 96dp variant (no resume row above it) scales with it.
 */
private val NEW_POSTER_WIDTH = 112.scaledDp()

/** How far the finger travels before a release refreshes. Material's default is 80dp. */
private val PULL_THRESHOLD = 130.dp

/** Content moves this fraction of the pull, so the page trails the finger. */
private const val PULL_FOLLOW = 0.6f

/** Past this much of the threshold the page stops following: the rubber band is spent. */
private const val MAX_PULL = 1.6f

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
        Box(Modifier.size(30.scaledDp()).border(1.dp, colors.onMediaBorder, PillShape), contentAlignment = Alignment.Center) {
            Icon(painterResource(if (refreshing) R.drawable.rg_ic_collapse else R.drawable.rg_ic_arrow_up), contentDescription = null, tint = colors.ink, modifier = Modifier.size(15.scaledDp()))
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
            Box(Modifier.size(44.scaledDp()).border(1.dp, colors.raised, androidx.compose.foundation.shape.RoundedCornerShape(13.dp)), contentAlignment = Alignment.Center) {
                Icon(painterResource(R.drawable.rg_ic_server), contentDescription = null, tint = colors.body, modifier = Modifier.size(21.scaledDp()))
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
