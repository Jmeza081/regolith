package com.regolith.ui.search

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
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import com.regolith.ui.components.ArtworkImage
import com.regolith.ui.components.Eyebrow
import com.regolith.ui.components.SurfaceCard
import com.regolith.ui.theme.PillShape
import com.regolith.ui.theme.RegolithTheme
import com.regolith.ui.theme.Spacing
import com.regolith.ui.theme.TextStyles
import com.regolith.ui.theme.designSp

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
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = RegolithTheme.colors
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    Column(modifier.fillMaxSize().statusBarsPadding().testTag("search_screen")) {
        Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.s18, vertical = Spacing.s12), verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.weight(1f).height(44.dp).clip(PillShape).background(colors.surface).border(1.dp, colors.raised, PillShape).padding(horizontal = Spacing.s12),
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
            Text(
                "Cancel", style = TextStyles.buttonTertiary, color = colors.ink,
                modifier = Modifier.clickable(interactionSource = null, indication = null, onClick = onCancel).testTag("search_cancel_button"),
            )
        }

        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = Spacing.s18, end = Spacing.s18, bottom = 112.dp),
            verticalArrangement = Arrangement.spacedBy(Spacing.s18),
        ) {
            // The filter chips leave with the results: with no matches the empty card sits under the field (design frame 24).
            if (!(state.searched && state.hits.isEmpty())) item {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s8)) {
                    SearchFilter.entries.forEach { f ->
                        val selected = f == state.filter
                        Box(
                            Modifier.height(34.dp).clip(PillShape)
                                .background(if (selected) colors.accent else colors.frostBg)
                                .then(if (selected) Modifier else Modifier.border(1.dp, colors.frostBorder, PillShape))
                                .clickable(interactionSource = null, indication = null) { viewModel.setFilter(f) }
                                .padding(horizontal = Spacing.s12)
                                .testTag("search_filter_${f.name.lowercase()}"),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(f.label, style = if (selected) TextStyles.chipSelected.copy(fontSize = 12.designSp()) else TextStyles.buttonSmall.copy(fontSize = 12.designSp()), color = if (selected) Color.White else colors.inkSoft)
                        }
                    }
                }
            }
            if (state.searched && state.hits.isNotEmpty()) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.s8)) {
                        Eyebrow("${state.hits.size} match" + (if (state.hits.size == 1) "" else "es"), muted = true, modifier = Modifier.testTag("search_count"))
                        Column(verticalArrangement = Arrangement.spacedBy(Spacing.s12)) {
                            state.hits.forEach { hit -> HitRow(hit, state.query, onOpenTitle, onOpenFolder, viewModel::remember) }
                        }
                    }
                }
            }
            if (state.searched && state.hits.isEmpty()) {
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
}

/** One result: an 82dp thumb (or the folder box), the name with the matched run in red, the meta. */
@Composable
private fun HitRow(hit: SearchHit, query: String, onOpenTitle: (Long) -> Unit, onOpenFolder: (Long) -> Unit, remember: () -> Unit) {
    val colors = RegolithTheme.colors
    Row(
        Modifier.fillMaxWidth().clickable(interactionSource = null, indication = null) {
            remember()
            when (hit) {
                is SearchHit.Title -> onOpenTitle(hit.fileId)
                is SearchHit.File -> onOpenTitle(hit.fileId)
                is SearchHit.Folder -> onOpenFolder(hit.folderId)
            }
        }.testTag(hit.testTag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(82.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(10.dp))) {
            when (hit) {
                is SearchHit.Folder -> Box(Modifier.fillMaxSize().background(colors.surface).border(1.dp, colors.hairline, RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
                    Icon(painterResource(R.drawable.rg_ic_folder_small), contentDescription = null, tint = colors.ink, modifier = Modifier.size(17.dp))
                }
                is SearchHit.Title -> ArtworkImage(ArtworkRequest(ArtworkOwner.File(hit.fileId), ArtworkKind.THUMB), Modifier.fillMaxSize(), fallbackLabel = hit.primary)
                is SearchHit.File -> ArtworkImage(ArtworkRequest(ArtworkOwner.File(hit.fileId), ArtworkKind.THUMB), Modifier.fillMaxSize(), fallbackLabel = hit.primary)
            }
        }
        Spacer(Modifier.width(Spacing.s12))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.s2)) {
            Text(highlight(hit.primary, query, colors.accent), style = TextStyles.rowLabelMedium, color = colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(hit.meta, style = TextStyles.meta, color = colors.metadata, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
