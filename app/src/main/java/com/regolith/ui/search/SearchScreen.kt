package com.regolith.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.regolith.R
import com.regolith.domain.artwork.ArtworkKind
import com.regolith.domain.artwork.ArtworkOwner
import com.regolith.domain.artwork.ArtworkRequest
import com.regolith.domain.library.ViewMode
import com.regolith.ui.components.LocalNavPillInsets
import com.regolith.ui.components.LocalSelectionChrome
import com.regolith.ui.components.SelectionChromeState
import com.regolith.ui.components.SelectionVerb
import com.regolith.ui.components.ArtworkImage
import com.regolith.ui.components.Eyebrow
import com.regolith.ui.components.FilterChip
import com.regolith.ui.components.MediaTile
import com.regolith.ui.components.RowAction
import com.regolith.ui.components.viewModeAction
import com.regolith.ui.util.formatClock
import com.regolith.ui.components.Tag
import com.regolith.ui.components.SurfaceCard
import com.regolith.ui.theme.PillShape
import com.regolith.ui.theme.RegolithTheme
import com.regolith.ui.theme.Spacing
import com.regolith.ui.theme.TextStyles
import com.regolith.ui.theme.designSp
import com.regolith.ui.theme.scaledDp
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.draw.alpha
import com.regolith.ui.util.SelectionUiState

/**
 * Search (design section 06). A 44dp pill field with the glyph, a red
 * caret and a clear button; "Cancel" beside it; four 34dp filter pills
 * (the selected one red); then "N MATCHES" and rows with an 82dp 16:9
 * thumb, the matched run in red, and the meta line. While a scan walks,
 * a card at the foot says matches will keep arriving. No matches gets
 * the card that says why, and Recent stays one tap from a hit.
 *
 * A toggle at the end of the first results label switches BOTH groups
 * between those rows and a grid of 16:9 tiles, and the choice is kept
 * (`AppPreferences.searchViewMode`). Only the rows show the matched run in
 * red; a tile shows the name as it is.
 */
@Composable
fun SearchScreen(
    viewModel: SearchViewModel,
    onCancel: () -> Unit,
    onOpenTitle: (fileId: Long) -> Unit,
    onOpenFolder: (folderId: Long) -> Unit,
    /** A point of interest: play this film from this time. */
    onPlayAt: (fileId: Long, startMs: Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = RegolithTheme.colors
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    val selection = state.selection
    val selecting = selection != null
    // Screen state, not ViewModel state: whether a sheet is open dies with
    // the screen, unlike the filter it sets.
    var momentSheet by remember { mutableStateOf(false) }
    // The chip row scrolls, and the moment chip is at the end of it. Picking
    // a moment from the sheet has to bring it into view, or the one chip
    // that says a filter is on would be the one you cannot see.
    val chipScroll = rememberScrollState()
    LaunchedEffect(state.poi) {
        if (state.poi != null) chipScroll.animateScrollTo(chipScroll.maxValue)
    }
    // No BackHandler: back leaves Search, and NavGraph clears the selection
    // when the top of the stack is somewhere that cannot act on it.

    Box(modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().statusBarsPadding().testTag("search_screen")) {
        Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.s18, vertical = Spacing.s12), verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.weight(1f).height(44.scaledDp()).clip(PillShape).background(colors.surface).border(1.dp, colors.raised, PillShape).padding(horizontal = Spacing.s12),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(painterResource(R.drawable.rg_ic_search), contentDescription = null, tint = colors.body, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(Spacing.s8))
                BasicTextField(
                    value = state.query,
                    onValueChange = viewModel::setQuery,
                    singleLine = true,
                    textStyle = TextStyles.body.copy(fontSize = 14.designSp(), lineHeight = 14.designSp(), color = colors.ink),
                    cursorBrush = SolidColor(colors.accent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { viewModel.remember() }),
                    decorationBox = { inner ->
                        Box {
                            if (state.query.isEmpty()) Text("Titles, filenames, folders", style = TextStyles.body.copy(fontSize = 14.designSp(), lineHeight = 14.designSp()), color = colors.metadata)
                            inner()
                        }
                    },
                    modifier = Modifier.weight(1f).focusRequester(focus).testTag("search_field"),
                )
                if (state.query.isNotEmpty()) {
                    Spacer(Modifier.width(Spacing.s8))
                    Box(
                        Modifier.size(20.dp).background(colors.raised, PillShape).clickable(interactionSource = null, indication = null) { viewModel.setQuery("") }.testTag("search_clear_button"),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(painterResource(R.drawable.rg_ic_close_small), contentDescription = "Clear", tint = colors.ink, modifier = Modifier.size(11.dp))
                    }
                }
            }
            Spacer(Modifier.width(Spacing.s12))
            // While selecting, this is "Select all" rather than a second
            // Cancel: the word would otherwise mean "leave Search" here and
            // "drop the picks" on the bar below, which is one word for two
            // different losses. Leaving the selection is the bar's Cancel or
            // the back gesture; this is the only door "Select all" has on
            // Search, since the screen has no top bar to hang an action on.
            Text(
                if (selecting) "Select all" else "Cancel",
                style = TextStyles.buttonTertiary, color = colors.ink,
                modifier = Modifier
                    .clickable(
                        interactionSource = null,
                        indication = null,
                        onClick = if (selecting) viewModel::selectAllHere else onCancel,
                    )
                    .testTag(if (selecting) "search_select_all_button" else "search_cancel_button"),
            )
        }

        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = Spacing.s18, end = Spacing.s18,
                bottom = LocalNavPillInsets.current.calculateBottomPadding(),
            ),
            verticalArrangement = Arrangement.spacedBy(Spacing.s18),
        ) {
            // The filter chips leave with the results: with no matches the empty card sits under the field (design frame 24).
            // A point of interest that narrowed to nothing is the exception —
            // the chips have to stay or there is no way to turn one off.
            if (!(state.searched && state.hits.isEmpty()) || state.poi != null) item {
                // One row, the moment chip in it like any other: five chips
                // do not fit a 411dp portrait, so the row scrolls. It opens a
                // sheet rather than toggling, because the names are the
                // library's and there can be two of them or forty — and it
                // wears the one in force, so the row says what is filtering.
                Row(
                    Modifier.horizontalScroll(chipScroll),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.s8),
                ) {
                    SearchFilter.entries.forEach { f ->
                        FilterChip(
                            text = f.label,
                            selected = f == state.filter,
                            onClick = { viewModel.setFilter(f) },
                            testTag = "search_filter_${f.name.lowercase()}",
                        )
                    }
                    if (state.facets.isNotEmpty()) {
                        FilterChip(
                            text = state.poi ?: "Moment",
                            selected = state.poi != null,
                            onClick = { momentSheet = true },
                            testTag = "search_filter_moment",
                            icon = R.drawable.rg_ic_sliders,
                            modifier = Modifier.widthIn(max = 160.dp),
                        )
                    }
                }
            }
            // Points of interest first, under their own eyebrow, so a red
            // match in a chapter's name is never mistaken for one in a
            // file's. They are places, not files: no selection, no download.
            if (state.searched && state.moments.isNotEmpty()) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.s8)) {
                        ResultsLabel("Points of interest", "search_moments", showToggle = true, state.viewMode, viewModel::toggleViewMode)
                        if (state.viewMode == ViewMode.GRID) {
                            HitGrid(state.moments) { hit, tileModifier ->
                                HitTile(hit, onOpenTitle, onOpenFolder, viewModel::remember, onPlayAt = onPlayAt, modifier = tileModifier)
                            }
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(Spacing.s12)) {
                                state.moments.forEach { hit ->
                                    HitRow(hit, state.query, onOpenTitle, onOpenFolder, viewModel::remember, onPlayAt = onPlayAt)
                                }
                            }
                        }
                    }
                }
            }
            if (state.searched && state.hits.isNotEmpty()) {
                item {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(Spacing.s8),
                        // s12 ON TOP OF the list's own s18 between items, so
                        // the seam between the two groups is s30 where every
                        // other gap on the screen is s18 — the next step up
                        // the scale, and the thing that tells you the wall
                        // has ended and another has started. Only when there
                        // IS a group above: alone, the matches are just the
                        // results and sit under the chips like anything else.
                        modifier = Modifier.padding(top = if (state.moments.isEmpty()) 0.dp else Spacing.s12),
                    ) {
                        // One toggle for both groups: it sits on whichever label comes first.
                        ResultsLabel(
                            "${state.hits.size} match" + (if (state.hits.size == 1) "" else "es"), "search_count",
                            showToggle = state.moments.isEmpty(), state.viewMode, viewModel::toggleViewMode,
                        )
                        if (state.viewMode == ViewMode.GRID) {
                            HitGrid(state.hits) { hit, tileModifier ->
                                HitTile(
                                    hit, onOpenTitle, onOpenFolder, viewModel::remember,
                                    selection = selection,
                                    onToggle = viewModel::toggleSelection,
                                    onLongPress = viewModel::beginSelection,
                                    modifier = tileModifier,
                                )
                            }
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(Spacing.s12)) {
                                state.hits.forEach { hit ->
                                    HitRow(
                                        hit, state.query, onOpenTitle, onOpenFolder, viewModel::remember,
                                        selection = selection,
                                        onToggle = viewModel::toggleSelection,
                                        onLongPress = viewModel::beginSelection,
                                    )
                                }
                            }
                        }
                    }
                }
            }
            if (state.searched && state.hits.isEmpty() && state.moments.isEmpty()) {
                item {
                    Column(
                        Modifier.fillMaxWidth().background(colors.surface, RoundedCornerShape(18.dp)).border(1.dp, colors.hairline, RoundedCornerShape(18.dp)).padding(Spacing.s18).testTag("search_empty_card"),
                        verticalArrangement = Arrangement.spacedBy(Spacing.s12),
                    ) {
                        Text("No file or folder matches that.", style = TextStyles.rowLabelMedium.copy(lineHeight = 21.designSp()), color = colors.ink)
                        Text("Search reads filenames as they are on the share, so spelling counts. Try fewer words, or drop the year.", style = TextStyles.settingMeta.copy(lineHeight = 18.designSp()), color = colors.body)
                    }
                }
            }
            state.scanningPath?.let { path ->
                item {
                    SurfaceCard(modifier = Modifier.fillMaxWidth().testTag("search_scanning_line"), contentPadding = PaddingValues(Spacing.s12)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(14.dp).border(1.5.dp, colors.raised, PillShape))
                            Spacer(Modifier.width(Spacing.s12))
                            Text("Still reading $path — matches will keep arriving.", style = TextStyles.settingMeta.copy(lineHeight = 17.designSp()), color = colors.body)
                        }
                    }
                }
            }
            if (state.recent.isNotEmpty() && (state.query.isBlank() || (state.searched && state.hits.isEmpty()))) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.s8)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Eyebrow("Recent", Modifier.weight(1f), muted = true)
                            Text(
                                "Clear", style = TextStyles.buttonSmall.copy(fontSize = 12.designSp()), color = colors.accent,
                                modifier = Modifier.clickable(interactionSource = null, indication = null, onClick = viewModel::clearRecent).testTag("search_clear_recent_button"),
                            )
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(Spacing.s2)) {
                            state.recent.forEach { q ->
                                Row(
                                    Modifier.fillMaxWidth().defaultMinSize(minHeight = 44.dp)
                                        .clickable(interactionSource = null, indication = null) { viewModel.setQuery(q) }
                                        .testTag("search_recent_$q"),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(painterResource(R.drawable.rg_ic_clock), contentDescription = null, tint = colors.body, modifier = Modifier.size(15.dp))
                                    Spacer(Modifier.width(Spacing.s12))
                                    Text(q, style = TextStyles.body.copy(lineHeight = 18.designSp()), color = colors.inkSoft)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

        // The pill becomes this selection's toolbar (SelectionChrome). This
        // screen only knows how to download a pick, so that is the one verb
        // it lends; Cancel is drawn by the pill itself.
        val selectionChrome = LocalSelectionChrome.current
        DisposableEffect(selection) {
            val live = selection
            if (live == null) {
                selectionChrome.clear()
            } else {
                selectionChrome.show(
                    SelectionChromeState(
                        verbs = listOf(
                            SelectionVerb("Download", R.drawable.rg_ic_download, viewModel::downloadSelection, "search_select_download", enabled = live.canDownload),
                        ),
                        onCancel = viewModel::cancelSelection,
                        summary = live.summary,
                        detail = live.detail,
                    ),
                )
            }
            onDispose { selectionChrome.clear() }
        }
        if (momentSheet) {
            MomentFilterSheet(
                facets = state.facets,
                selected = state.poi,
                onPick = { viewModel.setPoi(it); momentSheet = false },
                onDismiss = { momentSheet = false },
            )
        }
    }
}

/** One result: an 82dp thumb (or the folder box), the name with the matched run in red, the meta. */
@Composable
private fun HitRow(
    hit: SearchHit,
    query: String,
    onOpenTitle: (Long) -> Unit,
    onOpenFolder: (Long) -> Unit,
    remember: () -> Unit,
    selection: SelectionUiState? = null,
    onToggle: (SearchHit) -> Unit = {},
    onLongPress: (SearchHit) -> Unit = {},
    onPlayAt: (Long, Long) -> Unit = { _, _ -> },
) {
    val colors = RegolithTheme.colors
    val selecting = selection != null
    // Coming: picked itself, or inside a pick and not taken back out.
    // Nothing is inert — tapping a checked row inside a pick excludes it.
    val coming = selection.isComing(hit)
    val picked = coming
    val covered = false
    // A folder result is a way into Browse, so while selecting it keeps two
    // targets: the thumb picks it, the rest opens it. A file has nowhere to
    // walk into, so the whole row picks.
    val folderHit = hit is SearchHit.Folder
    val splitTargets = selecting && folderHit
    Row(
        Modifier
            .fillMaxWidth()
            .then(
                if (splitTargets) {
                    Modifier
                } else {
                    Modifier.combinedClickable(
                        interactionSource = null,
                        indication = null,
                        onLongClick = { if (hit !is SearchHit.Moment) onLongPress(hit) },
                    ) {
                        activate(hit, selecting, remember, onToggle, onOpenTitle, onOpenFolder, onPlayAt)
                    }
                },
            )
            .testTag(hit.testTag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .width(82.scaledDp())
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(10.dp))
                .then(
                    if (splitTargets) {
                        Modifier
                            .clickable(interactionSource = null, indication = null) { onToggle(hit) }
                            .testTag("${hit.testTag}_pick")
                    } else {
                        Modifier
                    },
                ),
        ) {
            when (hit) {
                is SearchHit.Folder -> Box(Modifier.fillMaxSize().background(colors.surface).border(1.dp, colors.hairline, RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
                    Icon(painterResource(R.drawable.rg_ic_folder_small), contentDescription = null, tint = colors.ink, modifier = Modifier.size(17.dp))
                }
                is SearchHit.Title -> ArtworkImage(ArtworkRequest(ArtworkOwner.File(hit.fileId), ArtworkKind.THUMB), Modifier.fillMaxSize(), fallbackLabel = hit.primary)
                is SearchHit.File -> ArtworkImage(ArtworkRequest(ArtworkOwner.File(hit.fileId), ArtworkKind.THUMB), Modifier.fillMaxSize(), fallbackLabel = hit.primary)
                // The frame at the mark itself, with the time over it. Two marks
                // in one film are two different pictures, which is the whole
                // point: the clock says where, and now the picture agrees.
                // Falls back to the film's own thumb when the seek cannot be
                // trusted (ArtworkRepository.resolveMoments).
                is SearchHit.Moment -> Box(Modifier.fillMaxSize()) {
                    ArtworkImage(ArtworkRequest(ArtworkOwner.Moment(hit.fileId, hit.startMs), ArtworkKind.THUMB), Modifier.fillMaxSize(), fallbackLabel = hit.meta)
                    Text(
                        formatClock(hit.startMs), style = TextStyles.meta, color = colors.inkSoft,
                        modifier = Modifier.align(Alignment.BottomStart).padding(3.dp).background(colors.overArt, PillShape).padding(horizontal = 5.dp, vertical = 1.dp),
                    )
                }
            }
        }
        Spacer(Modifier.width(Spacing.s12))
        Column(
            Modifier
                .weight(1f)
                .then(
                    if (splitTargets) {
                        Modifier
                            .combinedClickable(
                                interactionSource = null,
                                indication = null,
                                onLongClick = { onLongPress(hit) },
                            ) { (hit as SearchHit.Folder).let { onOpenFolder(it.folderId) } }
                            .testTag("${hit.testTag}_open")
                    } else {
                        Modifier
                    },
                ),
            verticalArrangement = Arrangement.spacedBy(Spacing.s2),
        ) {
            Text(highlight(hit.primary, query, colors.accent), style = TextStyles.rowLabelMedium, color = colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            // The meta line is highlighted as well as the title. A row now
            // stands for the whole file, so the match is just as likely to
            // be in the filename or the path down here as in the name above
            // -- and a result with nothing marked on it reads as a mistake.
            Text(highlight(hit.meta, query, colors.accent), style = TextStyles.meta, color = colors.metadata, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (hit is SearchHit.Moment) Tag("Point of interest", Modifier.padding(top = Spacing.s2))
        }
        if (picked) {
            Spacer(Modifier.width(Spacing.s12))
            Icon(
                painterResource(R.drawable.rg_ic_check),
                contentDescription = "Coming",
                tint = colors.ink,
                modifier = Modifier.size(18.scaledDp()),
            )
        }
    }
}

/**
 * Whether a result is coming in the selection: picked itself, or inside a
 * pick and not taken back out. Nothing is inert — tapping a checked result
 * inside a pick excludes it. A point of interest is a place, never picked.
 */
private fun SelectionUiState?.isComing(hit: SearchHit): Boolean = when (hit) {
    is SearchHit.Folder -> this?.pickedFolders?.contains(hit.folderId) == true ||
        this?.coversFolder(hit.shareId, hit.relPath) == true
    is SearchHit.Title -> this?.pickedFiles?.contains(hit.fileId) == true ||
        (this?.coversFile(hit.shareId, hit.relPath) == true && !this.excludedFiles.contains(hit.fileId))
    is SearchHit.File -> this?.pickedFiles?.contains(hit.fileId) == true ||
        (this?.coversFile(hit.shareId, hit.relPath) == true && !this.excludedFiles.contains(hit.fileId))
    is SearchHit.Moment -> false
}

/** What tapping a result does, row or tile: a moment plays from its time; while selecting a result is picked; otherwise it opens. */
private fun activate(
    hit: SearchHit,
    selecting: Boolean,
    remember: () -> Unit,
    onToggle: (SearchHit) -> Unit,
    onOpenTitle: (Long) -> Unit,
    onOpenFolder: (Long) -> Unit,
    onPlayAt: (Long, Long) -> Unit,
) {
    when {
        hit is SearchHit.Moment -> { remember(); onPlayAt(hit.fileId, hit.startMs) }
        selecting -> onToggle(hit)
        else -> {
            remember()
            when (hit) {
                is SearchHit.Title -> onOpenTitle(hit.fileId)
                is SearchHit.File -> onOpenTitle(hit.fileId)
                is SearchHit.Folder -> onOpenFolder(hit.folderId)
                is SearchHit.Moment -> Unit
            }
        }
    }
}

/**
 * A results group's label ("Points of interest", "3 matches"), with the
 * grid/rows toggle at its end when [showToggle]. Search has no top bar to
 * carry the toggle, so it rides on the first label of the results, and one
 * toggle switches both groups: a grid above a list would read as two
 * different kinds of thing.
 *
 * The LARGE eyebrow (13px, and #A0A0A0 rather than Search's usual muted
 * #6E6E6E), which is the same one Home gives its rows. Search is the only
 * screen that stacks two grids of the same-looking tiles, and at 11px muted
 * the label between them was quieter than the metadata under the tiles
 * either side of it — so the two groups read as one long wall. That is the
 * exact case [Eyebrow]'s `large` was added for: naming a GROUP, not
 * labelling the thing beneath it.
 */
@Composable
private fun ResultsLabel(text: String, tag: String, showToggle: Boolean, mode: ViewMode, onToggle: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Eyebrow(text, Modifier.weight(1f).testTag(tag), large = true)
        if (showToggle) {
            // The same glyph and words as the switch on Library and Browse.
            val action = viewModeAction(mode, "search_view_mode_button", onToggle)
            RowAction(icon = action.icon, contentDescription = action.contentDescription, onClick = action.onClick, testTag = action.testTag)
        }
    }
}

/**
 * Results as tiles: as many columns as fit at [GRID_TILE_MIN] each, two on
 * a phone and more on a wide window. Plain rows of tiles rather than a lazy
 * grid, because the groups already sit inside Search's scrolling list, and
 * a lazy grid cannot scroll inside another list.
 */
@Composable
private fun HitGrid(hits: List<SearchHit>, tile: @Composable (SearchHit, Modifier) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = (maxWidth / GRID_TILE_MIN).toInt().coerceIn(2, 4)
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.s12)) {
            hits.chunked(columns).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s8)) {
                    row.forEach { hit -> tile(hit, Modifier.weight(1f)) }
                    // The last row keeps the others' tile width instead of stretching.
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

/**
 * One result as a tile: the same 16:9 thumb Browse's grid shows, with the
 * name and meta line under it. A folder shows its count and, while
 * selecting, keeps two targets (the marker picks, the tile opens); a point
 * of interest wears its time as the chip over the frame AT that time.
 */
@Composable
private fun HitTile(
    hit: SearchHit,
    onOpenTitle: (Long) -> Unit,
    onOpenFolder: (Long) -> Unit,
    remember: () -> Unit,
    modifier: Modifier = Modifier,
    selection: SelectionUiState? = null,
    onToggle: (SearchHit) -> Unit = {},
    onLongPress: (SearchHit) -> Unit = {},
    onPlayAt: (Long, Long) -> Unit = { _, _ -> },
) {
    val selecting = selection != null
    val coming = selection.isComing(hit)
    val open = { activate(hit, selecting, remember, onToggle, onOpenTitle, onOpenFolder, onPlayAt) }
    when (hit) {
        is SearchHit.Folder -> MediaTile(
            artwork = ArtworkRequest(ArtworkOwner.Folder(hit.folderId), ArtworkKind.THUMB),
            kind = ArtworkKind.THUMB,
            title = hit.primary,
            meta = hit.meta,
            count = hit.fileCount.takeIf { it > 0 },
            onClick = { remember(); onOpenFolder(hit.folderId) },
            onLongClick = { onLongPress(hit) },
            checked = if (selecting) coming else null,
            onCheckClick = if (selecting) {
                { onToggle(hit) }
            } else {
                null
            },
            testTag = hit.testTag,
            modifier = modifier,
        )
        is SearchHit.Moment -> MediaTile(
            artwork = ArtworkRequest(ArtworkOwner.Moment(hit.fileId, hit.startMs), ArtworkKind.THUMB),
            kind = ArtworkKind.THUMB,
            title = hit.primary,
            meta = hit.meta,
            resolution = formatClock(hit.startMs),
            fallbackLabel = hit.meta,
            onClick = open,
            testTag = hit.testTag,
            modifier = modifier,
        )
        is SearchHit.Title, is SearchHit.File -> {
            val fileId = if (hit is SearchHit.Title) hit.fileId else (hit as SearchHit.File).fileId
            MediaTile(
                artwork = ArtworkRequest(ArtworkOwner.File(fileId), ArtworkKind.THUMB),
                kind = ArtworkKind.THUMB,
                title = hit.primary,
                meta = hit.meta,
                fallbackLabel = hit.primary,
                onClick = open,
                onLongClick = { onLongPress(hit) },
                checked = if (selecting) coming else null,
                testTag = hit.testTag,
                modifier = modifier,
            )
        }
    }
}

/** The narrowest a result tile gets before the grid drops a column. */
private val GRID_TILE_MIN = 160.dp

/** The matched run in red: the first case-insensitive occurrence of each query word. */
private fun highlight(text: String, query: String, accent: Color): AnnotatedString {
    val words = query.split(Regex("""[\s.\-_/]+""")).filter { it.isNotEmpty() }
    if (words.isEmpty()) return AnnotatedString(text)
    val ranges = words.mapNotNull { w ->
        val i = text.indexOf(w, ignoreCase = true)
        if (i < 0) null else i until i + w.length
    }.sortedBy { it.first }
    return buildAnnotatedString {
        var cursor = 0
        for (r in ranges) {
            if (r.first < cursor) continue
            append(text.substring(cursor, r.first))
            withStyle(SpanStyle(color = accent)) { append(text.substring(r.first, r.last + 1)) }
            cursor = r.last + 1
        }
        append(text.substring(cursor))
    }
}
