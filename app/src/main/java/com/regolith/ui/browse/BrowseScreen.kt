package com.regolith.ui.browse

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.composables.icons.lucide.R as LucideR
import com.regolith.ui.components.CardStyle
import com.regolith.ui.components.DisplayText
import com.regolith.ui.components.ErrorCard
import com.regolith.ui.components.ListRow
import com.regolith.ui.components.MediaTile
import com.regolith.ui.components.PrimaryButton
import com.regolith.ui.components.SurfaceCard
import com.regolith.ui.components.TertiaryButton
import com.regolith.ui.components.TopBar
import com.regolith.ui.theme.RegolithTheme
import com.regolith.ui.theme.Spacing
import com.regolith.ui.theme.TextStyles
import com.regolith.ui.util.formatBytes
import com.regolith.ui.util.formatRemaining

/**
 * Browse tab (design section 07, "Add Media"): the share as it actually is.
 * The root lists shares as rows; inside a folder everything is a two-across
 * grid of 16:9 tiles so full filenames stay readable. Folders carry a
 * corner mark, files show a frame pulled from the file. When the share is
 * out of reach the cached tiles stay, recessed to 45%, under a banner.
 *
 * Navigation shape: root -> share -> folder -> folder; a file opens Title
 * Detail, which holds the one red Play.
 */
@Composable
fun BrowseScreen(
    viewModel: BrowseViewModel,
    onBack: (() -> Unit)?,
    onOpenFolder: (folderId: Long) -> Unit,
    onOpenFile: (fileId: Long) -> Unit,
    onAddServer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = RegolithTheme.colors

    Column(modifier.fillMaxSize().testTag("browse_screen")) {
        TopBar(title = state.title, onBack = onBack, meta = state.breadcrumb)
        if (state.refreshing) {
            LinearProgressIndicator(color = colors.accent, trackColor = colors.hairline, modifier = Modifier.fillMaxWidth())
        }

        if (state.loaded && state.noSource) {
            NoSourceContent(onAddServer = onAddServer)
            return
        }

        val offline = state.offlineMessage
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.fillMaxSize().testTag("browse_grid"),
            contentPadding = PaddingValues(start = Spacing.s18, end = Spacing.s18, bottom = 120.dp),
            horizontalArrangement = Arrangement.spacedBy(Spacing.s12),
            verticalArrangement = Arrangement.spacedBy(Spacing.s18),
        ) {
            if (offline != null) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Column {
                        ErrorCard(message = offline, testTag = "browse_offline_card")
                        TertiaryButton(text = "Retry", onClick = viewModel::refresh, testTag = "browse_retry_button")
                    }
                }
            }
            items(
                state.rows,
                key = { it.testTag },
                span = { row -> GridItemSpan(if (row is BrowseRow.ShareRow) maxLineSpan else 1) },
            ) { row ->
                when (row) {
                    is BrowseRow.ShareRow -> ListRow(
                        title = row.name,
                        meta = listOfNotNull(row.serverName, row.freeBytes?.let { "${formatBytes(it)} free" }).joinToString(" · "),
                        icon = LucideR.drawable.lucide_ic_hard_drive,
                        onClick = { viewModel.openShare(row.shareId, onOpenFolder) },
                        testTag = row.testTag,
                    )
                    is BrowseRow.FolderRow -> MediaTile(
                        artwork = row.artwork,
                        title = row.name,
                        meta = if (row.fileCount > 0) "${row.fileCount} files · ${formatBytes(row.byteCount)}" else null,
                        folder = true,
                        dimmed = offline != null,
                        placeholderLabel = "Folder",
                        onClick = { onOpenFolder(row.folderId) },
                        testTag = row.testTag,
                    )
                    is BrowseRow.FileRow -> MediaTile(
                        artwork = row.artwork,
                        title = row.name,
                        meta = listOfNotNull(
                            row.resolutionLabel.ifEmpty { null },
                            formatBytes(row.sizeBytes),
                            if (row.progressMs != null && row.durationMs != null && row.progressMs > 0) formatRemaining(row.progressMs, row.durationMs) else null,
                        ).joinToString(" · "),
                        chip = row.resolutionLabel.ifEmpty { null },
                        dimmed = offline != null,
                        placeholderLabel = ".${row.ext.uppercase()}",
                        progress = if (row.progressMs != null && row.durationMs != null && row.durationMs > 0) row.progressMs.toFloat() / row.durationMs else null,
                        onClick = { onOpenFile(row.fileId) },
                        testTag = row.testTag,
                    )
                }
            }
            if (state.loaded && state.rows.isEmpty() && offline == null) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    SurfaceCard(style = CardStyle.Empty, modifier = Modifier.fillMaxWidth()) {
                        Text("Nothing playable in this folder.", style = TextStyles.body, color = colors.body)
                    }
                }
            }
        }
    }
}

@Composable
private fun NoSourceContent(onAddServer: () -> Unit) {
    val colors = RegolithTheme.colors
    Column(Modifier.padding(horizontal = Spacing.s18)) {
        Spacer(Modifier.height(Spacing.s30))
        SurfaceCard(style = CardStyle.Empty, modifier = Modifier.fillMaxWidth().testTag("browse_empty_card")) {
            DisplayText("No source server")
            Spacer(Modifier.height(Spacing.s8))
            Text(
                "Point Regolith at an SMB share and it lists what's on it. Films, recordings, anything it can play.",
                style = TextStyles.body,
                color = colors.body,
            )
            Spacer(Modifier.height(Spacing.s18))
            PrimaryButton(text = "Add source server", onClick = onAddServer, testTag = "browse_add_server_button", modifier = Modifier.fillMaxWidth())
        }
    }
}
