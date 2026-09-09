package com.regolith.ui.browse

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.regolith.R
import com.regolith.ui.components.CardStyle
import com.regolith.ui.components.DisplayText
import com.regolith.ui.components.Eyebrow
import com.regolith.ui.components.ListRow
import com.regolith.ui.components.NoticeCard
import com.regolith.ui.components.PrimaryButton
import com.regolith.ui.components.RowLeading
import com.regolith.ui.components.RowTrailing
import com.regolith.ui.components.SurfaceCard
import com.regolith.ui.components.TopBar
import com.regolith.ui.theme.RegolithTheme
import com.regolith.ui.theme.Spacing
import com.regolith.ui.theme.TextStyles
import com.regolith.ui.util.formatBytes
import com.regolith.ui.util.formatRemaining

/**
 * Browse (design section 07, "Add Media"): the share as it actually is.
 * "N FOLDERS" then a card of 56dp rows with a 34dp folder box and a
 * chevron; "N FILES" then a card of rows with a 52dp 16:9 thumb and the
 * filename. When the share is out of reach a notice card sits on top and
 * everything under it is recessed to 45%, saved from the last visit.
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
    val offline = state.offlineMessage

    Column(modifier.fillMaxSize().testTag("browse_screen")) {
        // No back arrow (design frame 25): the pill and the system back gesture do the navigating, and the breadcrumb says where you are.
        TopBar(title = "Add media", subtitle = state.breadcrumb, subtitleMuted = true)

        if (state.loaded && state.noSource) {
            NoSourceContent(onAddServer = onAddServer)
            return
        }

        val shares = state.rows.filterIsInstance<BrowseRow.ShareRow>()
        val folders = state.rows.filterIsInstance<BrowseRow.FolderRow>()
        val files = state.rows.filterIsInstance<BrowseRow.FileRow>()

        LazyColumn(
            modifier = Modifier.fillMaxSize().testTag("browse_grid"),
            contentPadding = PaddingValues(start = Spacing.s18, end = Spacing.s18, bottom = 112.dp),
            verticalArrangement = Arrangement.spacedBy(Spacing.s12),
        ) {
            if (offline != null) {
                item {
                    NoticeCard(
                        message = "Couldn't reach ${state.title.lowercase()} — showing what's saved here",
                        detail = "Saved from your last visit. Pull down to try the share again.",
                        testTag = "browse_offline_card",
                        action = {
                            Text(
                                "Retry", style = TextStyles.buttonTertiary, color = colors.ink,
                                modifier = Modifier.clickable(interactionSource = null, indication = null, onClick = viewModel::refresh).testTag("browse_retry_button"),
                            )
                        },
                    )
                }
            }
            if (shares.isNotEmpty()) {
                item {
                    Section("${shares.size} share" + (if (shares.size == 1) "" else "s")) {
                        shares.forEach { row ->
                            ListRow(
                                title = row.name,
                                meta = listOfNotNull(row.serverName, row.freeBytes?.let { "${formatBytes(it)} free" }).joinToString(" · "),
                                leading = RowLeading.IconBox(R.drawable.rg_ic_server),
                                onClick = { viewModel.openShare(row.shareId, onOpenFolder) },
                                testTag = row.testTag,
                            )
                        }
                    }
                }
            }
            if (folders.isNotEmpty()) {
                item {
                    Section("${folders.size} folder" + (if (folders.size == 1) "" else "s"), dimmed = offline != null) {
                        folders.forEach { row ->
                            ListRow(
                                title = row.name,
                                meta = if (row.fileCount > 0) "${row.fileCount} files · ${formatBytes(row.byteCount)}" else null,
                                leading = RowLeading.IconBox(R.drawable.rg_ic_browse),
                                onClick = { onOpenFolder(row.folderId) },
                                testTag = row.testTag,
                            )
                        }
                    }
                }
            }
            if (files.isNotEmpty()) {
                item {
                    Section("${files.size} file" + (if (files.size == 1) "" else "s"), dimmed = offline != null) {
                        files.forEach { row ->
                            ListRow(
                                title = row.name,
                                meta = listOfNotNull(
                                    row.resolutionLabel.ifEmpty { null },
                                    formatBytes(row.sizeBytes),
                                    if (row.progressMs != null && row.durationMs != null && row.progressMs > 0) formatRemaining(row.progressMs, row.durationMs) else null,
                                ).joinToString(" · "),
                                leading = RowLeading.Thumb(row.artwork, fallbackLabel = row.name),
                                trailing = RowTrailing.None,
                                compact = true,
                                onClick = { onOpenFile(row.fileId) },
                                testTag = row.testTag,
                            )
                        }
                    }
                }
            }
            if (state.loaded && state.rows.isEmpty() && offline == null) {
                item {
                    SurfaceCard(style = CardStyle.Empty, modifier = Modifier.fillMaxWidth()) {
                        Text("Nothing playable in this folder.", style = TextStyles.body, color = colors.body)
                    }
                }
            }
        }
    }
}

/** A muted eyebrow over a card of rows (12dp side padding, rows carry their own height). */
@Composable
private fun Section(label: String, dimmed: Boolean = false, content: @Composable () -> Unit) {
    Column(Modifier.alpha(if (dimmed) 0.45f else 1f), verticalArrangement = Arrangement.spacedBy(Spacing.s8)) {
        Eyebrow(label, muted = true)
        SurfaceCard(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = Spacing.s12)) { content() }
    }
}

@Composable
private fun NoSourceContent(onAddServer: () -> Unit) {
    val colors = RegolithTheme.colors
    Column(Modifier.padding(horizontal = Spacing.s18)) {
        Spacer(Modifier.height(Spacing.s30))
        SurfaceCard(style = CardStyle.Empty, modifier = Modifier.fillMaxWidth().testTag("browse_empty_card")) {
            DisplayText("No source server", style = TextStyles.emptyTitle)
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
