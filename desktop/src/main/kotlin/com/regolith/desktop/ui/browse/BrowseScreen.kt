package com.regolith.desktop.ui.browse

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.regolith.desktop.AppGraph
import com.regolith.desktop.navigation.Route
import com.regolith.desktop.ui.components.Eyebrow
import com.regolith.desktop.ui.components.ProblemCard
import com.regolith.desktop.ui.components.QuietButton
import com.regolith.desktop.ui.components.SecondaryButton
import com.regolith.desktop.ui.formatSize
import com.regolith.ui.theme.RegolithTheme
import com.regolith.domain.smb.SmbEntry

/** One folder of the share: open a subfolder, or open a film to edit its chapters. */
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

    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            QuietButton("‹ Back", onBack, Modifier.testTag("browse_back_button"))
            Column(Modifier.weight(1f)) {
                Text(state.title, style = MaterialTheme.typography.titleLarge, color = RegolithTheme.colors.ink)
                Eyebrow(state.location)
            }
            SecondaryButton("Refresh", vm::refresh, enabled = !state.loading, modifier = Modifier.testTag("browse_refresh_button"))
        }
        HorizontalDivider(Modifier.padding(vertical = 12.dp), color = RegolithTheme.colors.hairline)
        state.problem?.let { ProblemCard(it, Modifier.testTag("browse_problem").padding(bottom = 12.dp)) }
        when {
            state.loading && state.rows.isEmpty() ->
                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = RegolithTheme.colors.body)
                }
            !state.loading && state.rows.isEmpty() && state.problem == null ->
                Text("No folders or films here.", color = RegolithTheme.colors.body, modifier = Modifier.testTag("browse_empty"))
            else -> LazyColumn(Modifier.testTag("browse_list")) {
                items(state.rows, key = { it.entry.name }) { row ->
                    val openRow = {
                        val name = row.entry.name
                        if (row.isFolder) onOpenFolder(if (route.folder.isEmpty()) name else "${route.folder}/$name")
                        else onOpenFilm(row.entry)
                    }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable(onClick = openRow)
                            .testTag("browse_row")
                            .padding(horizontal = 8.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Text(row.entry.name, style = MaterialTheme.typography.bodyLarge, color = RegolithTheme.colors.ink, modifier = Modifier.weight(1f))
                        if (row.hasChapters) Eyebrow("Chapters")
                        Text(
                            if (row.isFolder) "›" else formatSize(row.entry.sizeBytes),
                            style = MaterialTheme.typography.bodyMedium,
                            color = RegolithTheme.colors.metadata,
                        )
                    }
                    HorizontalDivider(color = RegolithTheme.colors.hairline)
                }
            }
        }
    }
}
