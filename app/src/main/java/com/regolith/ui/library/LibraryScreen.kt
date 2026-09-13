package com.regolith.ui.library

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.regolith.R
import com.regolith.domain.artwork.ArtworkKind
import com.regolith.domain.artwork.ArtworkOwner
import com.regolith.domain.artwork.ArtworkRequest
import com.regolith.domain.library.LibrarySort
import com.regolith.domain.transfer.TransferCause
import com.regolith.domain.transfer.TransferStatus
import com.regolith.ui.components.LocalNavPillInsets
import com.regolith.ui.components.ArtworkImage
import com.regolith.ui.components.CardStyle
import com.regolith.ui.components.DisplayText
import com.regolith.ui.components.Eyebrow
import com.regolith.ui.components.OrbitArt
import com.regolith.ui.components.MediaTile
import com.regolith.ui.components.PlayAllButton
import com.regolith.ui.components.PlayAllSheet
import com.regolith.ui.components.PrimaryButton
import com.regolith.ui.components.Segment
import com.regolith.ui.components.RegolithSheet
import com.regolith.ui.components.SegmentedTabs
import com.regolith.ui.components.SheetOption
import com.regolith.ui.components.Skeleton
import com.regolith.ui.components.SurfaceCard
import com.regolith.ui.components.TopBar
import com.regolith.ui.components.TopBarAction
import com.regolith.ui.theme.RegolithTheme
import com.regolith.ui.theme.SheetShape
import com.regolith.ui.theme.Spacing
import com.regolith.ui.theme.TextStyles
import com.regolith.ui.theme.designSp
import com.regolith.ui.theme.TileShape
import com.regolith.ui.util.formatBytes
import com.regolith.ui.util.formatFileCount
import com.regolith.ui.util.formatWhen
import com.regolith.ui.theme.ThumbShape
import com.regolith.ui.components.viewModeAction
import com.regolith.ui.components.RowTrailing
import com.regolith.ui.components.RowLeading
import com.regolith.ui.components.ListRow
import com.regolith.domain.library.ViewMode
import androidx.compose.foundation.lazy.itemsIndexed
import com.regolith.ui.theme.scaledDp
import androidx.activity.compose.BackHandler
import com.regolith.ui.components.SelectionBar
import com.regolith.ui.components.SELECTION_BAR_HEIGHT
import com.regolith.ui.util.SelectionUiState
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import com.regolith.ui.theme.PillShape
import androidx.compose.ui.window.Dialog
import com.regolith.ui.theme.DialogShape
import com.regolith.ui.components.DestructiveButton
import com.regolith.ui.components.SecondaryButton
import androidx.compose.runtime.LaunchedEffect

private enum class LibraryTab { NETWORK, ON_DEVICE }

/**
 * Library, the poster wall (design section 05, where it is titled MEDIA):
 * three across at 8dp gaps, poster first, the header reduced to the share
 * name with search and sort at 44dp on the right, the Network / On this
 * device switcher, skeletons at rest while the first scan walks, the sort
 * sheet, and the out-of-reach and device states.
 *
 * With [onBack] non-null this is one collection's wall.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel,
    onBack: (() -> Unit)?,
    onOpenCollection: (folderId: Long) -> Unit,
    onOpenTitle: (fileId: Long) -> Unit,
    onSearch: () -> Unit,
    onAddServer: () -> Unit,
    /**
     * Play all / Shuffle from this collection: the file ids in the order the
     * wall is showing them, [shuffle] saying whether to scramble that order.
     * Null on the Library root, where "all" would mean the whole NAS.
     */
    onPlayAll: ((fileIds: List<Long>, shuffle: Boolean) -> Unit)? = null,
    modifier: Modifier = Modifier,
    startOnDevice: Boolean = false,
    /**
     * The title open in the detail pane beside this wall, on a wide window.
     * The nav graph reads it off the back stack; on a phone it is always
     * null because Title Detail is a pushed screen, not a pane.
     */
    selectedFileId: Long? = null,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = RegolithTheme.colors
    var tab by remember { mutableStateOf(if (startOnDevice) LibraryTab.ON_DEVICE else LibraryTab.NETWORK) }
    var playAllOpen by remember { mutableStateOf(false) }
    val unreachable = state.unreachable
    val readyCount = state.device.ready.size

    val selection = state.selection
    val selecting = selection != null

    // Each selection belongs to one tab, and the two mean opposite things —
    // download these, delete these. Switching tabs ends whichever one you
    // walked away from, so the bar never counts things the tab cannot show.
    LaunchedEffect(tab) {
        if (tab == LibraryTab.ON_DEVICE) viewModel.cancelSelection() else viewModel.cancelDeviceSelection()
    }
    // No BackHandler: back walks out of the collection and the selection
    // comes with it, so a pick can span a wall and the folders under it.
    // Leaving selection is the X above or Cancel below.

    Box(modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().testTag("library_screen")) {
        val subtitle = when {
            tab == LibraryTab.ON_DEVICE -> "${formatBytes(state.device.usedBytes)} of ${formatBytes(state.device.totalBytes)} · plays with no network"
            unreachable.isNotEmpty() -> "${unreachable.first().name} unreachable · $readyCount file${if (readyCount == 1) "" else "s"} playable here"
            else -> state.meta
        }
        val devicePickCount = state.device.picked?.size
        if (devicePickCount != null) {
            // Removing copies, not picking downloads: a different selection
            // with its own count, and the same chrome so the gesture reads
            // the same wherever you use it.
            TopBar(
                title = if (devicePickCount == 1) "1 selected" else "$devicePickCount selected",
                onBack = onBack,
                modifier = Modifier.testTag("device_selection_topbar"),
                actions = listOf(
                    TopBarAction(R.drawable.rg_ic_check, "Select all", "device_select_all_button", viewModel::selectAllOnDevice),
                    TopBarAction(R.drawable.rg_ic_close, "Cancel selection", "device_select_cancel_button", viewModel::cancelDeviceSelection),
                ),
            )
        } else if (selecting) {
            TopBar(
                title = if (selection!!.itemCount == 1) "1 selected" else "${selection.itemCount} selected",
                // The screen's own back, not a cancel: it walks out of the
                // collection and the picks come with it. Cancelling is the X.
                onBack = onBack,
                modifier = Modifier.testTag("library_selection_topbar"),
                actions = listOf(
                    TopBarAction(R.drawable.rg_ic_check, "Select all", "library_select_all_button", viewModel::selectAllHere),
                    TopBarAction(R.drawable.rg_ic_close, "Cancel selection", "library_select_cancel_button", viewModel::cancelSelection),
                ),
            )
        } else {
            TopBar(
                title = if (onBack == null) "Media" else state.title,
                onBack = onBack,
                subtitle = subtitle,
                subtitleMuted = tab == LibraryTab.ON_DEVICE || unreachable.isNotEmpty(),
                actions = listOf(
                    TopBarAction(R.drawable.rg_ic_search_alt, "Search", "library_search_button", onSearch),
                    TopBarAction(R.drawable.rg_ic_sort, "Sort", "library_sort_button") { viewModel.openSortSheet(true) },
                    // One button, two lists: it toggles whichever tab you are on.
                    if (tab == LibraryTab.ON_DEVICE) {
                        viewModeAction(state.device.viewMode, "library_view_mode_button", viewModel::toggleDeviceViewMode)
                    } else {
                        viewModeAction(state.viewMode, "library_view_mode_button", viewModel::toggleViewMode)
                    },
                ),
            )
        }

        if (state.loaded && state.noSource) {
            NoSource(onAddServer)
            return
        }

        if (onBack == null) {
            SegmentedTabs(
                segments = listOf(
                    Segment("Network", "library_tab_network"),
                    Segment("On this device", "library_tab_device", count = readyCount.takeIf { it > 0 }),
                ),
                selected = if (tab == LibraryTab.NETWORK) 0 else 1,
                onSelect = { tab = if (it == 0) LibraryTab.NETWORK else LibraryTab.ON_DEVICE },
                // The top bar's subtitle ends 8dp above this; on its own that read as
                // one block of text with a control stuck to it.
                modifier = Modifier.padding(start = Spacing.s18, end = Spacing.s18, top = Spacing.s12, bottom = Spacing.s18),
            )
        }

        // The collection's own CTA. Titles only: a Collection tile is a folder
        // of folders and has no single file to start with, so a wall of them
        // has nothing to play in order.
        val playable = state.tiles.filterIsInstance<LibraryTile.Title>()
        if (onPlayAll != null && tab == LibraryTab.NETWORK && playable.isNotEmpty()) {
            PlayAllButton(
                onClick = { playAllOpen = true },
                modifier = Modifier.padding(start = Spacing.s18, end = Spacing.s18, bottom = Spacing.s12),
            )
        }

        if (tab == LibraryTab.ON_DEVICE) {
            // Its own Box so the remove bar floats over the list rather than
            // stacking under it; this branch returns before the wall's own.
            Box(Modifier.fillMaxSize()) {
                DeviceTab(
                    state = state.device,
                    onOpenTitle = onOpenTitle,
                    onRetry = viewModel::retryTransfer,
                    onCancel = viewModel::cancelTransfer,
                    onClearFailed = viewModel::clearFailed,
                    onToggleShowAll = viewModel::toggleShowAllFailed,
                    onLongPress = viewModel::beginDeviceSelection,
                    onToggle = viewModel::toggleDeviceSelection,
                    onClearAll = viewModel::askRemoveAll,
                )
                val devicePicked = state.device.picked
                if (devicePicked != null) {
                    val n = devicePicked.size
                    SelectionBar(
                        summary = when {
                            n == 0 -> "Nothing picked"
                            n == 1 -> "1 video · ${formatBytes(state.device.pickedBytes())}"
                            else -> "$n videos · ${formatBytes(state.device.pickedBytes())}"
                        },
                        detail = if (n == 0) {
                            "Hold or tap a copy to start"
                        } else {
                            "The share keeps them — this frees the space here"
                        },
                        actionText = "Remove",
                        actionEnabled = n > 0,
                        destructive = true,
                        onAction = viewModel::askRemovePicked,
                        onCancel = viewModel::cancelDeviceSelection,
                        testTag = "device_select_bar",
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
            if (state.device.confirmRemove != null) {
                RemoveCopiesDialog(
                    count = state.device.confirmCount,
                    onConfirm = viewModel::confirmRemove,
                    onKeep = viewModel::dismissRemoveConfirm,
                )
            }
            return
        }

        // Both layouts draw the same leading blocks and the same tiles; only
        // the container differs, so the decisions are made once, here.
        val showUnreachable = unreachable.isNotEmpty() && onBack == null
        val showSkeletons = !state.loaded || (state.tiles.isEmpty() && state.scanning)
        val showEmpty = state.loaded && state.tiles.isEmpty() && !state.scanning
        val showScanLine = state.scanning && state.tiles.isNotEmpty()
        val unreachableBlock = @Composable {
            OutOfReach(
                server = unreachable.first(),
                checking = state.checkingReachability,
                readyCount = readyCount,
                paused = state.device.inFlight.filter { it.status == TransferStatus.PAUSED },
                onTryAgain = viewModel::tryAgain,
                onGoDevice = { tab = LibraryTab.ON_DEVICE },
            )
        }
        val emptyBlock = @Composable { EmptyWall(scannedOnce = state.scannedOnce, onScan = viewModel::scanAll) }

        if (state.viewMode == ViewMode.GRID) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier.fillMaxSize().testTag("library_grid"),
                contentPadding = PaddingValues(
                    start = Spacing.s18, end = Spacing.s18,
                    bottom = LocalNavPillInsets.current.calculateBottomPadding() +
                        if (selecting) SELECTION_BAR_HEIGHT + Spacing.s8 else 0.dp,
                ),
                horizontalArrangement = Arrangement.spacedBy(Spacing.s8),
                verticalArrangement = Arrangement.spacedBy(Spacing.s8),
            ) {
                // Only the Network tab is affected by a share going away (design:
                // "the message lives there rather than over files that play fine").
                if (showUnreachable) item(span = { GridItemSpan(maxLineSpan) }) { unreachableBlock() }
                if (showSkeletons) {
                    items(9) { SkeletonTile() }
                    return@LazyVerticalGrid
                }
                if (showEmpty) {
                    item(span = { GridItemSpan(maxLineSpan) }) { emptyBlock() }
                    return@LazyVerticalGrid
                }
                if (showScanLine) item(span = { GridItemSpan(maxLineSpan) }) { ScanLine() }
                items(state.tiles, key = { it.testTag }) { tile ->
                    TileView(
                        tile, dimmed = unreachable.isNotEmpty(),
                        selected = !selecting && tile.isSelected(selectedFileId),
                        selection = selection,
                        onOpenCollection = onOpenCollection, onOpenTitle = onOpenTitle,
                        onToggle = viewModel::toggleSelection, onLongPress = viewModel::beginSelection,
                    )
                }
            }
        } else {
            // Rows: no card frame here. A wall can hold a thousand titles, and
            // the design's cards are for short grouped lists (Browse, Settings);
            // a hairline between rows is what keeps a long list readable.
            LazyColumn(
                modifier = Modifier.fillMaxSize().testTag("library_rows"),
                contentPadding = PaddingValues(
                    start = Spacing.s18, end = Spacing.s18,
                    bottom = LocalNavPillInsets.current.calculateBottomPadding() +
                        if (selecting) SELECTION_BAR_HEIGHT + Spacing.s8 else 0.dp,
                ),
            ) {
                if (showUnreachable) item { unreachableBlock() }
                if (showSkeletons) {
                    items(6) { SkeletonRow() }
                    return@LazyColumn
                }
                if (showEmpty) {
                    item { emptyBlock() }
                    return@LazyColumn
                }
                if (showScanLine) item { Box(Modifier.padding(bottom = Spacing.s8)) { ScanLine() } }
                itemsIndexed(state.tiles, key = { _, tile -> tile.testTag }) { index, tile ->
                    if (index > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(colors.hairline))
                    TileRow(
                        tile, dimmed = unreachable.isNotEmpty(),
                        selected = !selecting && tile.isSelected(selectedFileId),
                        selection = selection,
                        onOpenCollection = onOpenCollection, onOpenTitle = onOpenTitle,
                        onToggle = viewModel::toggleSelection, onLongPress = viewModel::beginSelection,
                    )
                }
            }
        }
    }

    if (playAllOpen && onPlayAll != null) {
        val playable = state.tiles.filterIsInstance<LibraryTile.Title>()
        PlayAllSheet(
            fileCount = playable.size,
            // Only a total we can stand behind: one unprobed file and the
            // sum would be quietly short, which is worse than no number.
            totalMs = playable.map { it.durationMs }.takeIf { d -> d.all { it != null } }?.filterNotNull()?.sum(),
            firstName = playable.firstOrNull()?.name,
            onPlay = { shuffle ->
                playAllOpen = false
                onPlayAll(playable.map { it.fileId }, shuffle)
            },
            onDismiss = { playAllOpen = false },
        )
    }

    if (state.sortSheetOpen) {
        SortSheet(selected = state.sort, onSelect = viewModel::setSort, onDismiss = { viewModel.openSortSheet(false) })
    }

        if (selection != null) {
            SelectionBar(
                summary = selection.summary,
                detail = selection.detail,
                actionEnabled = selection.canDownload,
                onAction = viewModel::downloadSelection,
                onCancel = viewModel::cancelSelection,
                testTag = "library_select_bar",
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

/** Is this the title the detail pane is showing? Collections are never selected: they open a wall, not a detail. */
private fun LibraryTile.isSelected(selectedFileId: Long?): Boolean =
    selectedFileId != null && this is LibraryTile.Title && fileId == selectedFileId

/** One tile on the wall. Both layouts hand a [LibraryTile] to the same two components. */
@Composable
private fun TileView(
    tile: LibraryTile,
    dimmed: Boolean,
    selected: Boolean = false,
    selection: SelectionUiState? = null,
    onOpenCollection: (Long) -> Unit,
    onOpenTitle: (Long) -> Unit,
    onToggle: (LibraryTile) -> Unit = {},
    onLongPress: (LibraryTile) -> Unit = {},
) {
    val selecting = selection != null
    when (tile) {
        is LibraryTile.Collection -> {
            val picked = selection?.pickedFolders?.contains(tile.folderId) == true
            val coming = picked || selection?.coversFolder(tile.shareId, tile.relPath) == true
            val out = selection?.leftOutInside(tile.shareId, tile.relPath) ?: 0
            // A collection is a way in, so the tile keeps opening it and the
            // marker does the picking — otherwise picking one would be the
            // last thing you could do to it. Inside a pick the marker is
            // still live: it takes this collection back out.
            MediaTile(
                artwork = tile.artwork,
                kind = ArtworkKind.POSTER,
                title = tile.name,
                meta = if (out > 0) "All but $out" else formatFileCount(tile.fileCount),
                count = tile.fileCount,
                resolution = tile.resolutionLabel.ifEmpty { null },
                dimmed = dimmed,
                onClick = { onOpenCollection(tile.folderId) },
                onLongClick = { onLongPress(tile) },
                checked = if (selecting) coming else null,
                onCheckClick = if (selecting) {
                    { onToggle(tile) }
                } else {
                    null
                },
                testTag = tile.testTag,
            )
        }
        is LibraryTile.Title -> {
            val coming = selection?.pickedFiles?.contains(tile.fileId) == true ||
                (selection?.coversFile(tile.shareId, tile.folderRelPath) == true && selection.excludedFiles.contains(tile.fileId).not())
            MediaTile(
                artwork = tile.artwork,
                kind = ArtworkKind.POSTER,
                title = tile.name,
                meta = tile.meta,
                resolution = tile.resolutionLabel.ifEmpty { null },
                // The unwatched dot shares the pick marker's corner, so it
                // stands down while selecting rather than sitting under it.
                unwatched = tile.unwatched && !selecting,
                matched = tile.matched,
                fallbackLabel = tile.fileName,
                progress = tile.progress,
                dimmed = dimmed,
                selected = selected,
                onClick = if (selecting) {
                    { onToggle(tile) }
                } else {
                    { onOpenTitle(tile.fileId) }
                },
                onLongClick = { onLongPress(tile) },
                checked = if (selecting) coming else null,
                testTag = tile.testTag,
            )
        }
    }
}

/**
 * The same tile as one row: a 34dp poster, the name, and the line the tile
 * would have carried under it, with the resolution folded in so nothing is
 * lost by switching layout. A collection keeps its chevron; a title has
 * nowhere further to go and drops it.
 */
@Composable
private fun TileRow(
    tile: LibraryTile,
    dimmed: Boolean,
    selected: Boolean = false,
    selection: SelectionUiState? = null,
    onOpenCollection: (Long) -> Unit,
    onOpenTitle: (Long) -> Unit,
    onToggle: (LibraryTile) -> Unit = {},
    onLongPress: (LibraryTile) -> Unit = {},
) {
    val selecting = selection != null
    val alpha = if (dimmed) 0.45f else 1f
    // A row has no art to ring, so the selected one is lifted onto the card surface.
    val selectedBg = if (selected) Modifier.background(RegolithTheme.colors.surface) else Modifier
    when (tile) {
        is LibraryTile.Collection -> {
            val picked = selection?.pickedFolders?.contains(tile.folderId) == true
            val coming = picked || selection?.coversFolder(tile.shareId, tile.relPath) == true
            val out = selection?.leftOutInside(tile.shareId, tile.relPath) ?: 0
            ListRow(
                title = tile.name,
                meta = if (out > 0) {
                    "All but $out"
                } else {
                    listOfNotNull(formatFileCount(tile.fileCount), tile.resolutionLabel.ifEmpty { null }).joinToString(" · ")
                },
                // While selecting the poster gives way to the pick box, so the
                // row has a target that picks and a target that opens.
                leading = if (selecting) {
                    RowLeading.PickBox(R.drawable.rg_ic_browse, picked = coming)
                } else {
                    RowLeading.Poster(tile.artwork, fallbackLabel = tile.name)
                },
                minHeight = 64.scaledDp(),
                trailing = RowTrailing.Chevron,
                onClick = { onOpenCollection(tile.folderId) },
                onLeadingClick = if (selecting) {
                    { onToggle(tile) }
                } else {
                    null
                },
                leadingDescription = if (coming) "${tile.name}, coming" else "Pick ${tile.name}",
                onLongClick = { onLongPress(tile) },
                testTag = tile.testTag,
                modifier = Modifier.alpha(alpha),
            )
        }
        is LibraryTile.Title -> {
            val coming = selection?.pickedFiles?.contains(tile.fileId) == true ||
                (selection?.coversFile(tile.shareId, tile.folderRelPath) == true && selection.excludedFiles.contains(tile.fileId).not())
            ListRow(
                title = if (tile.matched) tile.name else tile.fileName,
                meta = listOfNotNull(tile.resolutionLabel.ifEmpty { null }, tile.meta.ifEmpty { null }).joinToString(" · "),
                leading = RowLeading.Poster(tile.artwork, fallbackLabel = tile.fileName),
                trailing = if (coming) RowTrailing.Checked else RowTrailing.None,
                minHeight = 64.scaledDp(),
                onClick = if (selecting) {
                    { onToggle(tile) }
                } else {
                    { onOpenTitle(tile.fileId) }
                },
                onLongClick = { onLongPress(tile) },
                testTag = tile.testTag,
                modifier = selectedBg.alpha(alpha),
            )
        }
    }
}

/**
 * The one confirm on the On-this-device page.
 *
 * Deleting a copy is cheap to undo in principle — the file is still on the
 * share — and expensive in practice, because getting it back is another
 * download over SMB. So it asks, and it says which of those two facts
 * matters: the share is untouched, the time is not.
 *
 * Same shape as Settings' disconnect dialog: one destructive button, one
 * way out, and the count in the title so "Clear all" and a selection of
 * three are visibly different acts.
 */
@Composable
private fun RemoveCopiesDialog(count: Int, onConfirm: () -> Unit, onKeep: () -> Unit) {
    val colors = RegolithTheme.colors
    Dialog(onDismissRequest = onKeep) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(colors.surface, DialogShape)
                .border(1.dp, colors.raised, DialogShape)
                .padding(Spacing.s18)
                .testTag("device_remove_dialog"),
            verticalArrangement = Arrangement.spacedBy(Spacing.s12),
        ) {
            DisplayText(
                if (count == 1) "Remove 1 download?" else "Remove $count downloads?",
                style = TextStyles.dialogTitle,
            )
            Text(
                "The copies leave this device and the space comes back. Nothing on the share is touched — you can keep them again whenever you like, and it will be another download.",
                style = TextStyles.body, color = colors.body,
            )
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.s8)) {
                DestructiveButton(
                    text = if (count == 1) "Remove it" else "Remove $count",
                    onClick = onConfirm,
                    testTag = "device_remove_confirm_button",
                    modifier = Modifier.fillMaxWidth().height(48.scaledDp()),
                )
                SecondaryButton(
                    text = "Keep them",
                    onClick = onKeep,
                    testTag = "device_remove_keep_button",
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/** "Nothing here" / "Not scanned yet": the same card in either layout. */
@Composable
private fun EmptyWall(scannedOnce: Boolean, onScan: () -> Unit) {
    val colors = RegolithTheme.colors
    SurfaceCard(style = CardStyle.Empty, modifier = Modifier.fillMaxWidth().testTag("library_empty_card")) {
        DisplayText(if (scannedOnce) "Nothing here" else "Not scanned yet", style = TextStyles.dialogTitle)
        Spacer(Modifier.height(Spacing.s8))
        Text(
            if (scannedOnce) "The scan found nothing playable here." else "Regolith reads the share once to know what is on it. Nothing is copied off it.",
            style = TextStyles.body, color = colors.body,
        )
        if (!scannedOnce) {
            Spacer(Modifier.height(Spacing.s18))
            PrimaryButton(text = "Scan now", onClick = onScan, testTag = "library_scan_button", modifier = Modifier.fillMaxWidth())
        }
    }
}

/** "Still reading the share": the wall fills in behind it as the scan walks. */
@Composable
private fun ScanLine() {
    Text(
        "Still reading the share · more will arrive",
        style = TextStyles.meta, color = RegolithTheme.colors.metadata,
        modifier = Modifier.testTag("library_scanning_line"),
    )
}

/**
 * The sort sheet (design: "Sheets step up to #0F0F0F with a 22px top
 * radius. The check is the only red on the screen."): a 38×4 handle,
 * "SORT BY" in Michroma 14, five 48dp rows at 500 15/20.
 */
@Composable
private fun SortSheet(selected: LibrarySort, onSelect: (LibrarySort) -> Unit, onDismiss: () -> Unit) {
    RegolithSheet(title = "Sort by", onDismiss = onDismiss, testTag = "library_sort_sheet") {
        LibrarySort.entries.forEach { sort ->
            SheetOption(
                label = sort.label,
                selected = sort == selected,
                onClick = { onSelect(sort) },
                testTag = "library_sort_${sort.name.lowercase()}",
            )
        }
    }
}

/** A resting poster (design: "Media · loading"): a dark gradient with the soft highlight, then two bars. Stillness. */
@Composable
private fun SkeletonTile() {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.s4)) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(TileShape)
                .background(Brush.linearGradient(listOf(Color(0xFF1C2228), Color(0xFF0B0E11)))),
        ) {
            Box(Modifier.fillMaxSize().background(Brush.radialGradient(listOf(Color(0x24FFFFFF), Color.Transparent), radius = 420f)))
        }
        Skeleton(Modifier.fillMaxWidth().height(8.dp), shape = RoundedCornerShape(4.dp))
        Skeleton(Modifier.fillMaxWidth(0.7f).height(8.dp), shape = RoundedCornerShape(4.dp))
    }
}

/** A resting row while the first scan walks: the poster block and two bars. */
@Composable
private fun SkeletonRow() {
    Row(Modifier.fillMaxWidth().padding(vertical = Spacing.s8), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.width(34.scaledDp()).aspectRatio(2f / 3f).clip(ThumbShape)
                .background(Brush.linearGradient(listOf(Color(0xFF1C2228), Color(0xFF0B0E11)))),
        )
        Spacer(Modifier.width(Spacing.s12))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.s8)) {
            Skeleton(Modifier.fillMaxWidth(0.6f).height(8.dp), shape = RoundedCornerShape(4.dp))
            Skeleton(Modifier.fillMaxWidth(0.35f).height(8.dp), shape = RoundedCornerShape(4.dp))
        }
    }
}

/**
 * The out-of-reach block (design: "Media · network out of reach"): an 18dp
 * card at #141414 with the wifi-off glyph, "TOWER is out of reach" at
 * 500 14/20, the last-seen line at 12/18, "Try again" as plain text; then
 * the red way across to the device tab; then "Waiting for the share".
 */
@Composable
private fun OutOfReach(
    server: UnreachableServer,
    checking: Boolean,
    readyCount: Int,
    paused: List<DeviceRow>,
    onTryAgain: () -> Unit,
    onGoDevice: () -> Unit,
) {
    val colors = RegolithTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.s18), modifier = Modifier.padding(bottom = Spacing.s12).testTag("library_unreachable_card")) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.s18)) {
            Row(
                Modifier.fillMaxWidth().background(colors.noticeBg, RoundedCornerShape(18.dp)).padding(Spacing.s18),
                verticalAlignment = Alignment.Top,
            ) {
                Icon(painterResource(R.drawable.rg_ic_wifi_off), contentDescription = null, tint = colors.body, modifier = Modifier.size(19.scaledDp()))
                Spacer(Modifier.width(Spacing.s12))
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.s8)) {
                    Text("${server.name} is out of reach", style = TextStyles.rowLabelMedium.copy(lineHeight = 20.designSp()), color = colors.inkSoft)
                    Text(
                        (server.lastSeenAtMs?.let { "Last seen ${formatWhen(it)}. " } ?: "") + "Nothing on the share can be listed until the phone is back on that network.",
                        style = TextStyles.settingMeta.copy(lineHeight = 18.designSp()), color = colors.body,
                    )
                    Text(
                        if (checking) "Checking…" else "Try again", style = TextStyles.buttonTertiary, color = colors.ink,
                        modifier = Modifier.clickable(enabled = !checking, interactionSource = null, indication = null, onClick = onTryAgain).testTag("library_try_again_button"),
                    )
                }
            }
            if (readyCount > 0) {
                PrimaryButton(
                    text = "Play the $readyCount file${if (readyCount == 1) "" else "s"} on this device",
                    onClick = onGoDevice,
                    leadingIcon = painterResource(R.drawable.rg_ic_download_alt),
                    testTag = "library_go_device_button",
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        if (paused.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.s8)) {
                Eyebrow("Waiting for the share", muted = true)
                paused.forEach { row ->
                    Row(
                        Modifier.fillMaxWidth().background(colors.surface, RoundedCornerShape(18.dp)).padding(Spacing.s18),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.width(60.scaledDp()).aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp)).alpha(0.4f)) {
                            ArtworkImage(ArtworkRequest(ArtworkOwner.File(row.fileId), ArtworkKind.THUMB), Modifier.fillMaxSize(), fallbackLabel = row.name)
                        }
                        Spacer(Modifier.width(Spacing.s12))
                        Text(
                            "${row.name} · paused at ${formatBytes(row.bytesDone)} of ${formatBytes(row.totalBytes)}, resumes on its own",
                            style = TextStyles.settingMeta.copy(lineHeight = 18.designSp()), color = colors.body,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun NoSource(onAddServer: () -> Unit) {
    val colors = RegolithTheme.colors
    // Design "Media · first run": the dashed card at 20dp corners, centred in the space above the pill.
    Box(Modifier.fillMaxSize().padding(start = Spacing.s18, end = Spacing.s18, bottom = LocalNavPillInsets.current.calculateBottomPadding()), contentAlignment = Alignment.Center) {
        Column(
            Modifier.fillMaxWidth().background(Color(0xFF050505), RoundedCornerShape(20.dp))
                .dashedBorder(colors.raised, RoundedCornerShape(20.dp))
                .padding(horizontal = Spacing.s18, vertical = Spacing.s30)
                .testTag("library_empty_card"),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.s18),
        ) {
            Icon(painterResource(R.drawable.rg_ic_server), contentDescription = null, tint = colors.body, modifier = Modifier.size(18.scaledDp()))
            DisplayText("No source server", style = TextStyles.dialogTitle, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Text(
                "Point Regolith at an SMB share and it lists what's on it — films, recordings, anything it can play.",
                style = TextStyles.body, color = colors.metadata, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            PrimaryButton(text = "Add source server", onClick = onAddServer, testTag = "library_add_server_button", modifier = Modifier.fillMaxWidth())
        }
    }
}

/** A 1dp dashed hairline, the only dashed border in the system ("nothing here yet"). */
private fun Modifier.dashedBorder(color: Color, shape: RoundedCornerShape): Modifier = this.then(
    Modifier.drawBehind {
        drawRoundRect(
            color = color,
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(20.dp.toPx()),
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.dp.toPx(), pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))),
        )
    },
)

/**
 * "On this device" (design: "On device · transfers"): files that play
 * first (64dp rows, 60dp thumbs at 9dp corners), then the failures with
 * the cause named per row (72dp rows, thumbs at 45%, "Try again" in
 * white), three at a time with "Show all" at the foot and "Clear all" in
 * red beside the eyebrow.
 */
@Composable
private fun DeviceTab(
    state: DeviceUiState,
    onOpenTitle: (Long) -> Unit,
    onRetry: (Long) -> Unit,
    onCancel: (Long) -> Unit,
    onClearFailed: () -> Unit,
    onToggleShowAll: () -> Unit,
    onLongPress: (Long) -> Unit = {},
    onToggle: (Long) -> Unit = {},
    onClearAll: () -> Unit = {},
) {
    val colors = RegolithTheme.colors
    val picked = state.picked
    val selecting = picked != null
    // One row renderer for all three sections, so picking behaves the same
    // whether a copy is ready, arriving or failed.
    val deviceRow: @Composable (DeviceRow, androidx.compose.ui.unit.Dp, String?, () -> Unit, Boolean) -> Unit =
        { row, minHeight, action, onAction, dimThumb ->
            DeviceRowView(
                row = row,
                minHeight = minHeight,
                onClick = { if (selecting) onToggle(row.fileId) else onOpenTitle(row.fileId) },
                onLongClick = { onLongPress(row.fileId) },
                checked = if (selecting) row.fileId in picked!! else null,
                action = action,
                onAction = onAction,
                dimThumb = dimThumb,
            )
        }
    LazyColumn(
        Modifier.fillMaxSize().testTag("library_device_list"),
        contentPadding = PaddingValues(
            start = Spacing.s18, end = Spacing.s18,
            bottom = if (selecting) 126.dp + SELECTION_BAR_HEIGHT + Spacing.s8 else 126.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(Spacing.s18),
    ) {
        if (state.ready.isEmpty() && state.inFlight.isEmpty() && state.failed.isEmpty()) {
            item {
                // The one empty state in the app that gets a drawing. It is
                // also the only one that is a normal resting state rather
                // than a fault: a share you have never scanned wants the
                // scan button, an empty folder wants one quiet line, and
                // this one wants to say "there is nothing wrong here".
                SurfaceCard(
                    style = CardStyle.Empty,
                    contentPadding = PaddingValues(horizontal = Spacing.s18, vertical = Spacing.s30),
                    modifier = Modifier.fillMaxWidth().testTag("library_device_empty"),
                ) {
                    OrbitArt(Modifier.align(Alignment.CenterHorizontally))
                    Spacer(Modifier.height(Spacing.s18))
                    DisplayText(
                        "Nothing in orbit", style = TextStyles.dialogTitle, textAlign = TextAlign.Center,
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    )
                    Spacer(Modifier.height(Spacing.s8))
                    Text(
                        "Open a title and choose \"Keep on this device\" — it lives here and plays with the share offline.",
                        style = TextStyles.body, color = colors.body, textAlign = TextAlign.Center,
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    )
                }
            }
            return@LazyColumn
        }
        if (state.ready.isNotEmpty()) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.s8)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Eyebrow("Ready offline", Modifier.weight(1f), muted = true)
                        // Beside its own eyebrow, the way "Clear failed" sits
                        // beside its own. The page's one page-level act, and
                        // it asks before it does anything.
                        if (!selecting) {
                            Text(
                                "Clear all", style = TextStyles.buttonSmall.copy(fontSize = 12.designSp()), color = colors.accent,
                                modifier = Modifier
                                    .clickable(interactionSource = null, indication = null, onClick = onClearAll)
                                    .testTag("device_clear_all_button"),
                            )
                        }
                    }
                    if (state.viewMode == ViewMode.GRID) {
                        // Chunked rows rather than a LazyVerticalGrid: this is
                        // already inside a LazyColumn, which cannot give a
                        // nested lazy grid a height to work with.
                        Column(verticalArrangement = Arrangement.spacedBy(Spacing.s12)) {
                            state.ready.chunked(DEVICE_COLUMNS).forEach { rowOfTiles ->
                                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s8)) {
                                    rowOfTiles.forEach { row ->
                                        MediaTile(
                                            artwork = ArtworkRequest(ArtworkOwner.File(row.fileId), ArtworkKind.POSTER),
                                            title = row.name,
                                            meta = row.meta,
                                            onClick = { if (selecting) onToggle(row.fileId) else onOpenTitle(row.fileId) },
                                            onLongClick = { onLongPress(row.fileId) },
                                            checked = if (selecting) row.fileId in picked!! else null,
                                            onCheckClick = { onToggle(row.fileId) },
                                            selected = selecting && row.fileId in picked!!,
                                            testTag = row.testTag,
                                            modifier = Modifier.weight(1f),
                                        )
                                    }
                                    repeat(DEVICE_COLUMNS - rowOfTiles.size) { Spacer(Modifier.weight(1f)) }
                                }
                            }
                        }
                    } else {
                        SurfaceCard(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = Spacing.s12)) {
                            state.ready.forEach { row -> deviceRow(row, 64.dp, null, {}, false) }
                        }
                    }
                }
            }
        }
        if (state.inFlight.isNotEmpty()) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.s8)) {
                    Eyebrow("Arriving", muted = true)
                    SurfaceCard(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = Spacing.s12)) {
                        state.inFlight.forEach { row -> deviceRow(row, 72.dp, "Cancel", { onCancel(row.fileId) }, false) }
                    }
                }
            }
        }
        if (state.failed.isNotEmpty()) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.s8)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Eyebrow("Failed · ${state.failed.size}", Modifier.weight(1f), muted = true)
                        Text(
                            // "Clear failed", not "Clear all": the page now has a
                            // Clear all of its own, and two of them meaning
                            // different amounts would be a trap.
                            "Clear failed", style = TextStyles.buttonSmall.copy(fontSize = 12.designSp()), color = colors.accent,
                            modifier = Modifier.clickable(interactionSource = null, indication = null, onClick = onClearFailed).testTag("device_clear_failed_button"),
                        )
                    }
                    SurfaceCard(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = Spacing.s12)) {
                        val shown = if (state.showAllFailed) state.failed else state.failed.take(3)
                        shown.forEach { row -> deviceRow(row, 72.dp, "Try again", { onRetry(row.fileId) }, true) }
                        if (state.failed.size > 3) {
                            Box(
                                Modifier.fillMaxWidth().defaultMinSize(minHeight = 44.dp)
                                    .clickable(interactionSource = null, indication = null, onClick = onToggleShowAll)
                                    .testTag("device_show_all_button"),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(if (state.showAllFailed) "Show fewer" else "Show all ${state.failed.size}", style = TextStyles.buttonSmall, color = colors.ink)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** One transfer row: a 60dp 16:9 thumb, name at 500 13/17, the cause or meta at 11/1.4, an optional 600 12 action. */
@Composable
private fun DeviceRowView(
    row: DeviceRow,
    minHeight: androidx.compose.ui.unit.Dp,
    onClick: () -> Unit,
    action: String?,
    onAction: () -> Unit,
    dimThumb: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    /** Null when not selecting; true when this copy is picked for removal. */
    checked: Boolean? = null,
) {
    val colors = RegolithTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = minHeight)
            .combinedClickable(interactionSource = null, indication = null, onClick = onClick, onLongClick = onLongClick)
            .padding(vertical = Spacing.s4)
            .testTag(row.testTag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // A copy has nowhere to walk into, so the whole row picks it and the
        // check rides on the thumbnail rather than taking a column of its own.
        Box(Modifier.width(60.scaledDp()).aspectRatio(16f / 9f).clip(RoundedCornerShape(9.dp)).alpha(if (dimThumb) 0.45f else 1f)) {
            ArtworkImage(ArtworkRequest(ArtworkOwner.File(row.fileId), ArtworkKind.THUMB), Modifier.fillMaxSize(), fallbackLabel = row.name)
            if (checked != null) {
                Box(Modifier.fillMaxSize().background(colors.overArt), contentAlignment = Alignment.Center) {
                    Box(
                        Modifier
                            .size(20.dp)
                            .background(if (checked) colors.ink else Color.Transparent, PillShape)
                            .then(if (checked) Modifier else Modifier.border(1.5.dp, colors.onMediaCircleBorder, PillShape)),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (checked) {
                            Icon(
                                painterResource(R.drawable.rg_ic_check),
                                contentDescription = "Picked",
                                tint = colors.ground,
                                modifier = Modifier.size(12.dp),
                            )
                        }
                    }
                }
            }
        }
        Spacer(Modifier.width(Spacing.s12))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.s2)) {
            Text(row.name, style = TextStyles.rowLabelSmall, color = colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                when (row.status) {
                    TransferStatus.DONE -> row.meta
                    TransferStatus.QUEUED -> "Queued · ${formatBytes(row.totalBytes)}"
                    TransferStatus.RUNNING -> "${formatBytes(row.bytesDone)} of ${formatBytes(row.totalBytes)}"
                    TransferStatus.PAUSED -> "Waiting for the share · ${formatBytes(row.bytesDone)}/${formatBytes(row.totalBytes)}"
                    TransferStatus.FAILED -> when (row.cause) {
                        TransferCause.NO_ROOM -> "No room · ${formatBytes(row.causeBytes ?: 0)} needed"
                        TransferCause.SHARE_DROPPED -> "Share dropped · ${formatBytes(row.bytesDone)}/${formatBytes(row.totalBytes)}"
                        else -> "The copy failed"
                    }
                },
                style = TextStyles.meta, color = if (row.status == TransferStatus.DONE) colors.metadata else colors.body, maxLines = 2,
            )
        }
        if (action != null && checked == null) {
            Spacer(Modifier.width(Spacing.s12))
            Text(
                action, style = TextStyles.buttonSmall.copy(fontSize = 12.designSp()), color = colors.ink,
                modifier = Modifier.clickable(interactionSource = null, indication = null, onClick = onAction).testTag("${row.testTag}_action"),
            )
        }
    }
}

/**
 * Tiles across the On this device grid. Three, like the Library wall, so
 * the two tabs read as the same kind of page at the same size.
 */
private const val DEVICE_COLUMNS = 3
