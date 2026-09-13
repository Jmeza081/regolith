package com.regolith.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import com.regolith.ui.components.LocalNavPillInsets
import com.regolith.ui.components.ArtworkImage
import com.regolith.ui.components.Eyebrow
import com.regolith.ui.components.FilterChip
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
import com.regolith.ui.components.SelectionBar
import com.regolith.ui.components.SELECTION_BAR_HEIGHT
import com.regolith.ui.util.SelectionUiState

/**
 * Search (design section 06). A 44dp pill field with the glyph, a red
 * caret and a clear button; "Cancel" beside it; four 34dp filter pills
 * (the selected one red); then "N MATCHES" and rows with an 82dp 16:9
 * thumb, the matched run in red, and the meta line. While a scan walks,
 * a card at the foot says matches will keep arriving. No matches gets
 * the card that says why, and Recent stays one tap from a hit.
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
                bottom = LocalNavPillInsets.current.calculateBottomPadding() +
                    if (selecting) SELECTION_BAR_HEIGHT + Spacing.s8 else 0.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(Spacing.s18),
        ) {
            // The filter chips leave with the results: with no matches the empty card sits under the field (design frame 24).
            // A point of interest that narrowed to nothing is the exception —
            // the chips have to stay or there is no way to turn one off.
            if (!(state.searched && state.hits.isEmpty()) || state.poi != null) item {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s8)) {
                    SearchFilter.entries.forEach { f ->
                        FilterChip(
                            text = f.label,
                            selected = f == state.filter,
                            onClick = { viewModel.setFilter(f) },
                            testTag = "search_filter_${f.name.lowercase()}",
                        )
                    }
                }
            }
            // Points of interest: the chapter names the library repeats,
            // whether typed in the editor or imported from a .chapters.txt.
            // A chip is a search in its own right, so this row stands even
            // with an empty field.
            if (state.facets.isNotEmpty()) item {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.s8)) {
                    Eyebrow("Filter by moment", muted = true)
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.s8),
                    ) {
                        state.facets.forEach { facet ->
                            FilterChip(
                                text = facet.title,
                                selected = facet.title.equals(state.poi, ignoreCase = true),
                                onClick = { viewModel.togglePoi(facet.title) },
                                testTag = "search_poi_${facet.title.lowercase().replace(' ', '_')}",
                                modifier = Modifier.widthIn(max = 200.dp),
                            )
                        }
                    }
                }
            }            // Points of interest first, under their own eyebrow, so a red
            // match in a chapter's name is never mistaken for one in a
            // file's. They are places, not files: no selection, no download.
            if (state.searched && state.moments.isNotEmpty()) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.s8)) {
                        Eyebrow("Points of interest", muted = true, modifier = Modifier.testTag("search_moments"))
                        Column(verticalArrangement = Arrangement.spacedBy(Spacing.s12)) {
                            state.moments.forEach { hit ->
                                HitRow(hit, state.query, onOpenTitle, onOpenFolder, viewModel::remember, onPlayAt = onPlayAt)
                            }
                        }
                    }
                }
            }
            if (state.searched && state.hits.isNotEmpty()) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.s8)) {
                        Eyebrow("${state.hits.size} match" + (if (state.hits.size == 1) "" else "es"), muted = true, modifier = Modifier.testTag("search_count"))
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

        if (selection != null) {
            SelectionBar(
                summary = selection.summary,
                detail = selection.detail,
                actionEnabled = selection.canDownload,
                onAction = viewModel::downloadSelection,
                onCancel = viewModel::cancelSelection,
                testTag = "search_select_bar",
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(
                        start = Spacing.s18,
                        end = Spacing.s18,
                        bottom = LocalNavPillInsets.current.calculateBottomPadding() + Spacing.s8,
                    ),
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
    val coming = when (hit) {
        is SearchHit.Folder -> selection?.pickedFolders?.contains(hit.folderId) == true ||
            selection?.coversFolder(hit.shareId, hit.relPath) == true
        is SearchHit.Title -> selection?.pickedFiles?.contains(hit.fileId) == true ||
            (selection?.coversFile(hit.shareId, hit.relPath) == true && selection.excludedFiles.contains(hit.fileId).not())
        is SearchHit.File -> selection?.pickedFiles?.contains(hit.fileId) == true ||
            (selection?.coversFile(hit.shareId, hit.relPath) == true && selection.excludedFiles.contains(hit.fileId).not())
        is SearchHit.Moment -> false
    }
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
                // The film's thumb with the time over it: the picture says
                // which film, the clock says where in it.
                is SearchHit.Moment -> Box(Modifier.fillMaxSize()) {
                    ArtworkImage(ArtworkRequest(ArtworkOwner.File(hit.fileId), ArtworkKind.THUMB), Modifier.fillMaxSize(), fallbackLabel = hit.meta)
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
            Text(hit.meta, style = TextStyles.meta, color = colors.metadata, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
