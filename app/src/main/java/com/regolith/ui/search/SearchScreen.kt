package com.regolith.ui.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.regolith.ui.components.CardStyle
import com.regolith.ui.components.Eyebrow
import com.regolith.ui.components.PillButton
import com.regolith.ui.components.RegolithTextField
import com.regolith.ui.components.SurfaceCard
import com.regolith.ui.components.TertiaryButton
import com.regolith.ui.theme.RegolithTheme
import com.regolith.ui.theme.Spacing
import com.regolith.ui.theme.TextStyles
import androidx.compose.foundation.clickable

/**
 * Search (design section 06). The field, Cancel, four filter pills,
 * then titles, raw filenames and folders in one list with the matched
 * run in red. While a scan walks, the footer says matches will keep
 * arriving rather than showing an empty state.
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
        Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.s18, vertical = Spacing.s8), verticalAlignment = Alignment.Bottom) {
            RegolithTextField(
                value = state.query,
                onValueChange = viewModel::setQuery,
                label = "Search",
                placeholder = "Titles, filenames, folders",
                testTag = "search_field",
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { viewModel.remember() }),
                modifier = Modifier.weight(1f).focusRequester(focus),
            )
            Spacer(Modifier.width(Spacing.s8))
            TertiaryButton(text = "Cancel", onClick = onCancel, testTag = "search_cancel_button")
        }
        Row(Modifier.padding(horizontal = Spacing.s18, vertical = Spacing.s4), horizontalArrangement = Arrangement.spacedBy(Spacing.s8)) {
            SearchFilter.entries.forEach { f ->
                PillButton(text = f.label, selected = f == state.filter, onMedia = false, onClick = { viewModel.setFilter(f) }, testTag = "search_filter_${f.name.lowercase()}")
            }
        }
        Spacer(Modifier.height(Spacing.s8))

        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = Spacing.s18, end = Spacing.s18, bottom = 120.dp)) {
            if (state.searched && state.hits.isNotEmpty()) {
                item {
                    Text("${state.hits.size} matches", style = TextStyles.metadata, color = colors.metadata, modifier = Modifier.padding(bottom = Spacing.s8).testTag("search_count"))
                }
            }
            items(state.hits, key = { it.testTag }) { hit ->
                Column(
                    Modifier.fillMaxWidth().clickable {
                        viewModel.remember()
                        when (hit) {
                            is SearchHit.Title -> onOpenTitle(hit.fileId)
                            is SearchHit.File -> onOpenTitle(hit.fileId)
                            is SearchHit.Folder -> onOpenFolder(hit.folderId)
                        }
                    }.padding(vertical = Spacing.s12).testTag(hit.testTag),
                ) {
                    Text(highlight(hit.primary, state.query, colors.accent), style = TextStyles.rowLabel, color = colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(hit.meta, style = TextStyles.metadata, color = colors.metadata, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                HorizontalDivider(color = colors.hairline, thickness = 1.dp)
            }
            if (state.searched && state.hits.isEmpty()) {
                item {
                    Spacer(Modifier.height(Spacing.s12))
                    SurfaceCard(style = CardStyle.Empty, modifier = Modifier.fillMaxWidth().testTag("search_empty_card")) {
                        Text("No file or folder matches that.", style = TextStyles.rowLabel, color = colors.ink)
                        Spacer(Modifier.height(Spacing.s4))
                        Text("Search reads filenames as they are on the share, so spelling counts. Try fewer words, or drop the year.", style = TextStyles.body, color = colors.body)
                    }
                }
            }
            state.scanningPath?.let { path ->
                item {
                    Text("Still reading $path — matches will keep arriving.", style = TextStyles.metadata, color = colors.metadata, modifier = Modifier.padding(vertical = Spacing.s12).testTag("search_scanning_line"))
                }
            }
            if (state.recent.isNotEmpty() && (state.query.isBlank() || (state.searched && state.hits.isEmpty()))) {
                item {
                    Spacer(Modifier.height(Spacing.s18))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Eyebrow("Recent", Modifier.weight(1f))
                        TertiaryButton(text = "Clear", onClick = viewModel::clearRecent, testTag = "search_clear_recent_button")
                    }
                }
                items(state.recent, key = { "recent_$it" }) { q ->
                    Text(
                        q, style = TextStyles.rowLabel, color = colors.ink,
                        modifier = Modifier.fillMaxWidth().clickable { viewModel.setQuery(q) }.padding(vertical = Spacing.s12).testTag("search_recent_$q"),
                    )
                    HorizontalDivider(color = colors.hairline, thickness = 1.dp)
                }
            }
        }
    }
}

/** The matched run in red: the first case-insensitive occurrence of each query word. */
private fun highlight(text: String, query: String, accent: androidx.compose.ui.graphics.Color): AnnotatedString {
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
