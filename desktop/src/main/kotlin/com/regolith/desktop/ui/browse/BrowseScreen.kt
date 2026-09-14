package com.regolith.desktop.ui.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.regolith.desktop.AppGraph
import com.regolith.desktop.navigation.Route
import com.regolith.domain.smb.SmbEntry
import com.regolith.ui.components.ErrorCard
import com.regolith.ui.components.Eyebrow
import com.regolith.ui.components.ListRow
import com.regolith.ui.components.RegolithIcons
import com.regolith.ui.components.RowLeading
import com.regolith.ui.components.RowTrailing
import com.regolith.ui.components.TopBar
import com.regolith.ui.components.TopBarAction
import com.regolith.ui.theme.RegolithTheme
import com.regolith.ui.theme.Spacing
import com.regolith.ui.theme.TextStyles
import com.regolith.ui.util.formatBytes
import com.regolith.ui.util.formatFileCount
import com.regolith.ui.util.formatFolderCount

/**
 * One folder of the share: open a subfolder, or open a film to edit its
 * chapters. Laid out like the phone's Browse in rows: a section per kind,
 * each a card of rows.
 */
@Composable
fun BrowseScreen(
    graph: AppGraph,
    route: Route.Browse,
    onBack: () -> Unit,
    onOpenFolder: (String) -> Unit,
    onOpenFilm: (SmbEntry) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val vm = remember(route) { BrowseViewModel(graph, route, scope) }
    val state by vm.state.collectAsState()
    val colors = RegolithTheme.colors
    val folderIcon = rememberVectorPainter(RegolithIcons.Browse)
    val refreshIcon = rememberVectorPainter(RegolithIcons.Refresh)

    fun open(row: BrowseRow) {
        val name = row.entry.name
        if (row.isFolder) onOpenFolder(if (route.folder.isEmpty()) name else "${route.folder}/$name") else onOpenFilm(row.entry)
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 16.dp)) {
        TopBar(
            title = state.title,
            subtitle = state.location,
            subtitleMuted = true,
            onBack = onBack,
            backTestTag = "browse_back_button",
            statusBarPadding = false,
            actions = listOf(TopBarAction(refreshIcon, "Refresh", "browse_refresh_button", vm::refresh)),
        )
        state.problem?.let { ErrorCard(it.message, Modifier.padding(bottom = Spacing.s12), testTag = "browse_problem", detail = it.detail) }
        when {
            state.loading && state.rows.isEmpty() ->
                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = colors.body)
                }
            !state.loading && state.rows.isEmpty() && state.problem == null ->
                Text("No folders or films here.", style = TextStyles.body, color = colors.body, modifier = Modifier.testTag("browse_empty"))
            else -> {
                val folders = state.rows.filter { it.isFolder }
                val films = state.rows.filterNot { it.isFolder }
                LazyColumn(Modifier.fillMaxWidth().testTag("browse_list")) {
                    section("folders", formatFolderCount(folders.size), folders) { row ->
                        ListRow(
                            title = row.entry.name,
                            onClick = { open(row) },
                            testTag = "browse_row",
                            leading = RowLeading.IconBox(folderIcon),
                            trailing = RowTrailing.Chevron,
                        )
                    }
                    section("films", formatFileCount(films.size), films) { row ->
                        ListRow(
                            title = row.entry.name,
                            meta = listOfNotNull(formatBytes(row.entry.sizeBytes), "Chapters".takeIf { row.hasChapters }).joinToString(" · "),
                            onClick = { open(row) },
                            testTag = "browse_row",
                            trailing = RowTrailing.None,
                        )
                    }
                }
            }
        }
    }
}

/**
 * A titled group of rows drawn as one card, as the phone draws its Browse
 * sections. Each row is its own lazy item, so a folder of hundreds of films
 * only composes what is on screen; the card is the rows' shared background,
 * rounded at the first and last.
 */
private fun LazyListScope.section(key: String, title: String, rows: List<BrowseRow>, row: @Composable (BrowseRow) -> Unit) {
    if (rows.isEmpty()) return
    item(key = "header:$key") { Eyebrow(title, Modifier.padding(top = Spacing.s18, bottom = Spacing.s8)) }
    itemsIndexed(rows, key = { _, r -> "$key:${r.entry.name}" }) { i, r ->
        val top = if (i == 0) 14.dp else 0.dp
        val bottom = if (i == rows.lastIndex) 14.dp else 0.dp
        Box(
            Modifier
                .fillMaxWidth()
                .background(RegolithTheme.colors.surface, RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom))
                .padding(horizontal = Spacing.s12),
        ) { row(r) }
    }
}
