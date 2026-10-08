package com.regolith.ui.home

import androidx.compose.foundation.background
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.regolith.R
import com.regolith.ui.adaptive.LocalWindowShape
import com.regolith.ui.components.LocalNavPillInsets
import com.regolith.ui.components.ArtworkImage
import com.regolith.ui.components.posterFlight
import com.regolith.ui.components.EmptyAction
import com.regolith.ui.components.EmptyState
import com.regolith.ui.components.Eyebrow
import com.regolith.ui.components.FilterChip
import com.regolith.ui.components.Ghost
import com.regolith.ui.components.ResumeCard
import com.regolith.ui.components.SurfaceCard
import com.regolith.ui.components.TopBar
import com.regolith.ui.components.TopBarAction
import com.regolith.ui.components.UnwatchedDot
import com.regolith.ui.components.WallPinchPill
import com.regolith.ui.components.rememberWallPinch
import com.regolith.ui.components.wallPinch
import com.regolith.ui.library.wallColumns
import com.regolith.ui.library.wallFitting
import com.regolith.ui.library.wallFullWidth
import com.regolith.ui.theme.PillShape
import com.regolith.ui.theme.RegolithTheme
import com.regolith.ui.theme.Spacing
import com.regolith.ui.theme.TextStyles
import com.regolith.ui.theme.TileShape
import com.regolith.ui.util.formatBytes
import com.regolith.ui.util.formatRemaining
import com.regolith.ui.theme.scaledDp

/**
 * Home tab (design section 04): resume first, then what arrived, then every
 * moment name in the library as a way into Search. The
 * states: first use (no server but this phone, and nothing on it), never
 * scanned, nothing found, resume, nothing started (no resume row at all:
 * its absence is the message), and the two halves of a pull to refresh,
 * which never blanks a screen that already has content.
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
    onEnterAddress: () -> Unit,
    onSearch: () -> Unit,
    onOpenTitle: (fileId: Long) -> Unit,
    onPlay: (fileId: Long, startMs: Long) -> Unit,
    onOpenDevice: () -> Unit,
    onOpenContinueWatching: () -> Unit,
    /** A chip in the Moments section: open Search with this moment name picked. */
    onOpenMoment: (name: String) -> Unit,
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
    // The inner display's Newly added wall follows Settings › Display ›
    // Posters per row, and a pinch on it steps that setting, as on the
    // Library's walls. Home never has a page beside it, so its wall always
    // has the whole width.
    val wallWidth = wallFullWidth()
    val across = wallColumns(wallWidth, wide = true, state.postersPerRow.count, wallWidth)
    val pinch = rememberWallPinch(shown = across, most = wallFitting(wallWidth), onStep = viewModel::setPostersPerRow)

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
            val nothingYet = state.resume.isEmpty() && state.newlyAdded.isEmpty() && state.onDevice.isEmpty()
            // First use. Phone storage alone is a source, but not one Home
            // fills from until it has videos, so it does not count here.
            if (!state.hasNetworkSource && nothingYet) {
                EmptyState(
                    title = "Your library starts with a server",
                    body = "Regolith plays what is already on your own network. Add the SMB share your videos live on, and what you watch and what arrives shows up here.",
                    ghost = Ghost.Shelves,
                    action = EmptyAction("Add source server", onAddServer, "home_add_server_button"),
                    link = EmptyAction("Enter an address", onEnterAddress, "home_enter_address_button"),
                    modifier = Modifier.padding(horizontal = Spacing.s18).padding(top = Spacing.s12),
                    testTag = "home_first_use",
                )
                return@Column
            }
            if (refreshing) Spacer(Modifier.height(30.dp + Spacing.s18))
            // Sections are told apart by the GAP between them, not by a rule
            // or a card: 18dp was the same gap as the one inside a section
            // (header to row), so the page read as one long list of rows
            // rather than three named groups.
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.s30)) {
                // Scanned, and nothing on any share Regolith can play. While a
                // scan walks, the refresh line above says enough.
                if (nothingYet && !state.neverScanned && !refreshing) {
                    EmptyState(
                        title = "Nothing here yet",
                        body = "The last scan found nothing Regolith can play. Add videos to the share, then scan again.",
                        ghost = Ghost.Shelves,
                        action = EmptyAction("Scan again", viewModel::refresh, "home_scan_again_button", primary = false),
                        modifier = Modifier.padding(horizontal = Spacing.s18).padding(top = Spacing.s12),
                        testTag = "home_empty",
                    )
                }
                if (state.resume.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.s8)) {
                        SectionHeader("Continue watching")
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
                                    // Its picture flies into the player it opens.
                                    flight = item.artwork.owner,
                                )
                            }
                            item {
                                SeeAllCard(
                                    width = resumeWidth,
                                    aspect = 16f / 9f,
                                    onClick = onOpenContinueWatching,
                                    testTag = "home_resume_all",
                                )
                            }
                        }
                    }
                }

                if (state.newlyAdded.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.s8)) {
                        Eyebrow("Newly added", Modifier.padding(horizontal = Spacing.s18), large = true)
                        if (wide) {
                            // The wall: rows of posters, edge to edge, as many across
                            // as the Library's walls. Home is a vertical scroll, so
                            // this is plain rows rather than a LazyVerticalGrid
                            // (which would need its own height). The tag stays the
                            // same so the QA flows find it either way.
                            Column(
                                Modifier.padding(horizontal = Spacing.s18).wallPinch(pinch).testTag("home_new_row"),
                                verticalArrangement = Arrangement.spacedBy(Spacing.s8),
                            ) {
                                state.newlyAdded.chunked(across).forEach { row ->
                                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s8)) {
                                        row.forEach { item -> NewPoster(item, onOpenTitle, Modifier.weight(1f)) }
                                        repeat(across - row.size) { Spacer(Modifier.weight(1f)) }
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
                    // The whole page when nothing else is here yet; one
                    // section among others when downloads already are.
                    EmptyState(
                        title = "Nothing scanned yet",
                        body = "Regolith reads the share once to know what is on it. Nothing is copied off it.",
                        ghost = if (nothingYet) Ghost.Shelves else Ghost.Posters(rows = 1),
                        compact = !nothingYet,
                        action = EmptyAction("Scan now", viewModel::refresh, "home_scan_button"),
                        modifier = Modifier.padding(horizontal = Spacing.s18).padding(top = if (nothingYet) Spacing.s12 else 0.dp),
                        testTag = "home_never_scanned",
                    )
                }

                // What is already here, as the films themselves rather than a
                // card counting them. Same tile as Newly added; the size the
                // card used to carry moves into the header, where it is still
                // the answer to "how much of the phone is this using".
                if (state.onDevice.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.s8)) {
                        SectionHeader(
                            title = "On this device",
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
                            item {
                                SeeAllCard(
                                    width = NEW_POSTER_WIDTH,
                                    aspect = 2f / 3f,
                                    onClick = onOpenDevice,
                                    testTag = "home_device_all",
                                )
                            }
                        }
                    }
                }

                // Every name you have given a moment, A to Z, wrapping in a
                // section of its own after the others (the owner, 2026-10-07:
                // "not hidden behind a horizontal scroll ... left to right and
                // wrapping"). Last, because it is a way into the library rather
                // than a shelf of things in it: a tap opens Search on that name,
                // already picked, listing every place it appears.
                if (state.moments.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.s8)) {
                        SectionHeader(
                            title = "Moments",
                            meta = "${state.moments.size} ${if (state.moments.size == 1) "name" else "names"} · A to Z",
                        )
                        FlowRow(
                            Modifier.padding(horizontal = Spacing.s18).testTag("home_moments"),
                            horizontalArrangement = Arrangement.spacedBy(Spacing.s8),
                            verticalArrangement = Arrangement.spacedBy(Spacing.s8),
                        ) {
                            state.moments.forEach { moment ->
                                FilterChip(
                                    text = moment.title,
                                    selected = false,
                                    onClick = { onOpenMoment(moment.title) },
                                    testTag = "home_moment_${moment.title.lowercase().replace(' ', '_')}",
                                    trailing = moment.films.toString(),
                                )
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(LocalNavPillInsets.current.calculateBottomPadding()))
        }
        // The pinch's "N across" pill, under the top bar. A TopBar is s12
        // above a 44dp row and s18 below; s8 here lifts the pill onto that gap.
        WallPinchPill(pinch, Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = Spacing.s12 + 44.dp + Spacing.s8))
    }
}

/**
 * The resume card. The design drew 186px on a 320px frame — a card and a
 * half in view; at 411dp that had become two and a half, and the row read
 * as small tiles rather than "the thing you were watching".
 */
private val RESUME_CARD_WIDTH = 256.dp

/**
 * A row's title, and an optional quiet fact beside it.
 *
 * The way to see all of a row used to be a word in this corner: "All", set
 * at link size, the smallest target on the screen for one of the more
 * useful moves. It is now the last card IN the row (see [SeeAllCard]), so
 * the header carries a title and nothing else.
 */
@Composable
private fun SectionHeader(
    title: String,
    meta: String? = null,
) {
    val colors = RegolithTheme.colors
    Row(
        Modifier.padding(horizontal = Spacing.s18),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(Spacing.s8),
    ) {
        Eyebrow(title, Modifier.weight(1f), large = true)
        meta?.let { Text(it, style = TextStyles.meta, color = colors.metadata) }
    }
}

/**
 * The last card in a row: a circled arrow that opens the whole list.
 *
 * It sits where the finger already is -- at the end of the row it has just
 * been scrolling -- and it is the size of the things beside it, which is
 * the entire point. The old "All" link was about 30dp wide in a corner the
 * thumb never visits.
 *
 * @param aspect the shape of the cards it follows, so it lines up with them
 *   rather than announcing itself as a different kind of object.
 */
@Composable
private fun SeeAllCard(
    width: Dp,
    aspect: Float,
    onClick: () -> Unit,
    testTag: String,
) {
    val colors = RegolithTheme.colors
    Column(
        Modifier
            .width(width)
            .aspectRatio(aspect)
            .clip(TileShape)
            .background(colors.frostBg)
            .clickable(interactionSource = null, indication = null, onClick = onClick)
            .testTag(testTag),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier.size(44.scaledDp()).border(1.dp, colors.frostBorder, PillShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painterResource(R.drawable.rg_ic_chevron_right),
                contentDescription = null,
                tint = colors.ink,
                modifier = Modifier.size(20.scaledDp()),
            )
        }
        Spacer(Modifier.height(Spacing.s8))
        Text("See all", style = TextStyles.buttonSmall, color = colors.ink)
    }
}

/** One "Newly added" poster: 2:3 art with the unwatched dot. The row and the wall draw the same one. */
@Composable
private fun NewPoster(item: NewItem, onOpenTitle: (fileId: Long) -> Unit, modifier: Modifier = Modifier) {
    val colors = RegolithTheme.colors
    Box(
        modifier
            .aspectRatio(2f / 3f)
            // It flies into the wide picture at the top of its page.
            .posterFlight(item.artwork.owner)
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

