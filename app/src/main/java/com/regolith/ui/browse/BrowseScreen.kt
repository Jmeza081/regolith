package com.regolith.ui.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.regolith.R
import com.regolith.ui.components.LocalNavPillInsets
import com.regolith.ui.components.CardStyle
import com.regolith.ui.components.DisplayText
import com.regolith.ui.components.Eyebrow
import com.regolith.ui.components.ListRow
import com.regolith.ui.components.PlayAllButton
import com.regolith.ui.components.PlayAllSheet
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
import com.regolith.ui.util.formatFileCount
import com.regolith.ui.util.formatFolderCount
import com.regolith.ui.util.formatRemaining
import com.regolith.ui.components.viewModeAction
import com.regolith.ui.components.MediaTile
import com.regolith.domain.library.ViewMode
import com.regolith.domain.artwork.ArtworkKind
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.GridCells

/**
 * Browse (design section 07): the share as it actually is.
 * "N FOLDERS" then a card of 56dp rows with a 34dp folder box and a
 * chevron; "N FILES" then a card of rows with a 52dp 16:9 thumb and the
 * filename. The top-bar switch swaps those rows for 16:9 tiles two across
 * (design frame 26); the choice is remembered. When the share is out of
 * reach a notice card sits on top and everything under it is recessed to
 * 45%, saved from the last visit.
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
    /**
     * A jump from the share tree. Unlike [onOpenFolder] it does not push:
     * the tree is a way back to the top, so it restarts the Browse chain
     * rather than growing it. Null on a phone, where there is no tree.
     */
    onOpenTree: ((folderId: Long) -> Unit)? = null,
    /**
     * Play all / Shuffle from this folder: the file ids in the order the list
     * is showing them. Null at the share list, where there are no files yet.
     */
    onPlayAll: ((fileIds: List<Long>, shuffle: Boolean) -> Unit)? = null,
    /**
     * The file open in the detail pane beside this wall on a wide window, so
     * the row or tile it came from is marked. Null on a phone, where opening
     * a file covers the wall.
     */
    selectedFileId: Long? = null,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val offline = state.offlineMessage

    // The tree stands beside the list only where both fit. Measured on this
    // screen's own width, not the window's: with a Title Detail pane open
    // beside it Browse is a third of the window, and the list has to win.
    BoxWithConstraints(modifier.fillMaxSize()) {
        val showTree = onOpenTree != null && maxWidth >= SHARE_TREE_MIN_WIDTH && state.tree.isNotEmpty() && !state.noSource
        Row(Modifier.fillMaxSize()) {
            if (showTree) {
                ShareTree(
                    nodes = state.tree,
                    currentFolderId = state.currentFolderId,
                    onOpen = onOpenTree!!,
                    modifier = Modifier.width(SHARE_TREE_WIDTH).fillMaxHeight().padding(top = TREE_TOP_PADDING, bottom = Spacing.s18),
                )
            }
            BrowseContent(state, offline, viewModel, onOpenFolder, onOpenFile, onAddServer, onPlayAll, selectedFileId, Modifier.weight(1f))
        }
    }
}

/** The folder list itself: the whole screen on a phone, the pane beside the tree on a wide window. */
@Composable
private fun BrowseContent(
    state: BrowseUiState,
    offline: String?,
    viewModel: BrowseViewModel,
    onOpenFolder: (folderId: Long) -> Unit,
    onOpenFile: (fileId: Long) -> Unit,
    onAddServer: () -> Unit,
    onPlayAll: ((fileIds: List<Long>, shuffle: Boolean) -> Unit)?,
    selectedFileId: Long?,
    modifier: Modifier,
) {
    val colors = RegolithTheme.colors
    var playAllOpen by remember { mutableStateOf(false) }
    Column(modifier.fillMaxSize().testTag("browse_screen")) {
        // No back arrow (design frame 25): the pill and the system back gesture do the navigating, and the breadcrumb says where you are.
        // The title is "Browse" at the root and the folder's own name inside one.
        TopBar(
            title = state.title,
            subtitle = state.breadcrumb,
            subtitleMuted = true,
            actions = listOf(viewModeAction(state.viewMode, "browse_view_mode_button", viewModel::toggleViewMode)),
        )

        if (state.loaded && state.noSource) {
            NoSourceContent(onAddServer = onAddServer)
            return
        }

        val shares = state.rows.filterIsInstance<BrowseRow.ShareRow>()
        val folders = state.rows.filterIsInstance<BrowseRow.FolderRow>()
        val files = state.rows.filterIsInstance<BrowseRow.FileRow>()

        // The folder's own CTA, above the list and only where there is
        // something to play: a folder of folders has nothing to queue.
        if (onPlayAll != null && files.isNotEmpty()) {
            PlayAllButton(
                onClick = { playAllOpen = true },
                modifier = Modifier.padding(start = Spacing.s18, end = Spacing.s18, bottom = Spacing.s12),
            )
        }
        if (playAllOpen && onPlayAll != null) {
            PlayAllSheet(
                fileCount = files.size,
                totalMs = files.map { it.durationMs }.takeIf { d -> d.all { it != null } }?.filterNotNull()?.sum(),
                firstName = files.firstOrNull()?.name,
                onPlay = { shuffle ->
                    playAllOpen = false
                    onPlayAll(files.map { it.fileId }, shuffle)
                },
                onDismiss = { playAllOpen = false },
            )
        }

        val offlineCard = @Composable {
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
        val emptyCard = @Composable {
            SurfaceCard(style = CardStyle.Empty, modifier = Modifier.fillMaxWidth()) {
                Text("Nothing playable in this folder.", style = TextStyles.body, color = colors.body)
            }
        }
        val showEmpty = state.loaded && state.rows.isEmpty() && offline == null
        // Shares are never tiles: a share has no artwork, and at the root the
        // question is "which share", not "which film".
        val shareSection = @Composable {
            if (shares.isNotEmpty()) {
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

        if (state.viewMode == ViewMode.ROWS) {
            LazyColumn(
                modifier = Modifier.fillMaxSize().testTag("browse_grid"),
                contentPadding = PaddingValues(start = Spacing.s18, end = Spacing.s18, bottom = LocalNavPillInsets.current.calculateBottomPadding()),
                verticalArrangement = Arrangement.spacedBy(Spacing.s12),
            ) {
                if (offline != null) item { offlineCard() }
                item { shareSection() }
                if (folders.isNotEmpty()) {
                    item {
                        Section(formatFolderCount(folders.size), dimmed = offline != null) {
                            folders.forEach { row ->
                                ListRow(
                                    title = row.name,
                                    meta = if (row.fileCount > 0) "${formatFileCount(row.fileCount)} · ${formatBytes(row.byteCount)}" else null,
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
                        Section(formatFileCount(files.size), dimmed = offline != null) {
                            files.forEach { row ->
                                ListRow(
                                    title = row.name,
                                    meta = fileMeta(row),
                                    leading = RowLeading.Thumb(row.artwork, fallbackLabel = row.name),
                                    trailing = RowTrailing.None,
                                    compact = true,
                                    onClick = { onOpenFile(row.fileId) },
                                    testTag = row.testTag,
                                    // A row has no art to ring, so the open one is lifted a step.
                                    // The Library's rows lift onto `surface` from the ground; these
                                    // already sit ON a surface card, so the same idea measured from
                                    // the card is `skeleton`, the next step up.
                                    modifier = if (row.fileId == selectedFileId) Modifier.background(colors.skeleton) else Modifier,
                                )
                            }
                        }
                    }
                }
                if (showEmpty) item { emptyCard() }
            }
        } else {
            // Tiles, two across (design frame 26). 16:9 rather than the
            // Library's 2:3: Browse shows the file as it is, and a folder on a
            // share has no poster to speak of.
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.fillMaxSize().testTag("browse_tiles"),
                contentPadding = PaddingValues(start = Spacing.s18, end = Spacing.s18, bottom = LocalNavPillInsets.current.calculateBottomPadding()),
                horizontalArrangement = Arrangement.spacedBy(Spacing.s8),
                verticalArrangement = Arrangement.spacedBy(Spacing.s12),
            ) {
                if (offline != null) item(span = { GridItemSpan(maxLineSpan) }) { offlineCard() }
                if (shares.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }) { shareSection() }
                if (folders.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) { Eyebrow(formatFolderCount(folders.size), muted = true) }
                    items(folders, key = { it.testTag }) { row ->
                        MediaTile(
                            artwork = row.artwork,
                            kind = ArtworkKind.THUMB,
                            title = row.name,
                            meta = if (row.fileCount > 0) "${formatFileCount(row.fileCount)} · ${formatBytes(row.byteCount)}" else null,
                            count = row.fileCount.takeIf { it > 0 },
                            dimmed = offline != null,
                            onClick = { onOpenFolder(row.folderId) },
                            testTag = row.testTag,
                        )
                    }
                }
                if (files.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) { Eyebrow(formatFileCount(files.size), muted = true) }
                    items(files, key = { it.testTag }) { row ->
                        MediaTile(
                            artwork = row.artwork,
                            kind = ArtworkKind.THUMB,
                            title = row.name,
                            meta = fileMeta(row),
                            resolution = row.resolutionLabel.ifEmpty { null },
                            progress = row.fraction,
                            dimmed = offline != null,
                            fallbackLabel = row.name,
                            selected = row.fileId == selectedFileId,
                            onClick = { onOpenFile(row.fileId) },
                            testTag = row.testTag,
                        )
                    }
                }
                if (showEmpty) item(span = { GridItemSpan(maxLineSpan) }) { emptyCard() }
            }
        }
    }
}

/** The tree's first line sits on the top bar's own baseline rather than the screen's edge. */
private val TREE_TOP_PADDING = 30.dp

/** "1080p · 4.0 GB · 15m left" — the same line in either layout. */
private fun fileMeta(row: BrowseRow.FileRow) = listOfNotNull(
    row.resolutionLabel.ifEmpty { null },
    formatBytes(row.sizeBytes),
    if (row.progressMs != null && row.durationMs != null && row.progressMs > 0) formatRemaining(row.progressMs, row.durationMs) else null,
).joinToString(" · ")

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
            DisplayText("No source server", style = TextStyles.dialogTitle)
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
