package com.regolith.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.composables.icons.lucide.R as LucideR
import com.regolith.domain.artwork.ArtworkKind
import com.regolith.domain.library.LibrarySort
import com.regolith.ui.components.CardStyle
import com.regolith.ui.components.DisplayText
import com.regolith.ui.components.Eyebrow
import com.regolith.ui.components.MediaTile
import com.regolith.ui.components.PillButton
import com.regolith.ui.components.PrimaryButton
import com.regolith.ui.components.SecondaryButton
import com.regolith.ui.components.TertiaryButton
import com.regolith.ui.util.formatWhen
import com.regolith.ui.util.formatBytes
import com.regolith.domain.transfer.TransferCause
import com.regolith.domain.transfer.TransferStatus
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items as listItems
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import com.regolith.ui.components.Skeleton
import com.regolith.ui.components.SurfaceCard
import com.regolith.ui.components.TopBar
import com.regolith.ui.theme.CardShape
import com.regolith.ui.theme.PillShape
import com.regolith.ui.theme.RegolithTheme
import com.regolith.ui.theme.SheetShape
import com.regolith.ui.theme.Spacing
import com.regolith.ui.theme.TextStyles

private enum class LibraryTab { NETWORK, ON_DEVICE }

/**
 * Library, the poster wall (design section 05): three across, poster
 * first, the app bar reduced to the share name. Network / On this device
 * tabs (the device tab fills in with downloads in Phase 5), a sort sheet,
 * and skeletons at #141414 while the first scan is still walking.
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
    modifier: Modifier = Modifier,
    /** Land on the device tab (Home's "On this device" summary). */
    startOnDevice: Boolean = false,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = RegolithTheme.colors
    var tab by remember { mutableStateOf(if (startOnDevice) LibraryTab.ON_DEVICE else LibraryTab.NETWORK) }

    Column(modifier.fillMaxSize().testTag("library_screen")) {
        Row(Modifier.fillMaxWidth().statusBarsPadding(), verticalAlignment = Alignment.CenterVertically) {
            TopBar(title = state.title, onBack = onBack, meta = state.meta, statusBarPadding = false, modifier = Modifier.weight(1f))
            IconButton(onClick = onSearch, modifier = Modifier.padding(end = Spacing.s8).testTag("library_search_button")) {
                Icon(painterResource(LucideR.drawable.lucide_ic_search), contentDescription = "Search", tint = colors.ink, modifier = Modifier.size(22.dp))
            }
            IconButton(onClick = { viewModel.openSortSheet(true) }, modifier = Modifier.padding(end = Spacing.s8).testTag("library_sort_button")) {
                Icon(painterResource(LucideR.drawable.lucide_ic_arrow_down_up), contentDescription = "Sort", tint = colors.ink, modifier = Modifier.size(22.dp))
            }
        }

        if (state.loaded && state.noSource) {
            NoSource(onAddServer)
            return
        }

        if (onBack == null) {
            Row(Modifier.padding(horizontal = Spacing.s18, vertical = Spacing.s8), horizontalArrangement = Arrangement.spacedBy(Spacing.s8)) {
                PillButton(text = "Network", selected = tab == LibraryTab.NETWORK, onMedia = false, onClick = { tab = LibraryTab.NETWORK }, testTag = "library_tab_network")
                PillButton(text = "On this device", selected = tab == LibraryTab.ON_DEVICE, onMedia = false, onClick = { tab = LibraryTab.ON_DEVICE }, testTag = "library_tab_device")
            }
        }

        if (tab == LibraryTab.ON_DEVICE) {
            DeviceTab(
                state = state.device,
                onOpenTitle = onOpenTitle,
                onRetry = viewModel::retryTransfer,
                onCancel = viewModel::cancelTransfer,
                onClearFailed = viewModel::clearFailed,
                onToggleShowAll = viewModel::toggleShowAllFailed,
            )
            return
        }

        // Only the Network tab is affected by a share going away (design:
        // "the message lives there rather than over files that play fine").
        val unreachable = state.unreachable
        if (unreachable.isNotEmpty() && onBack == null) {
            Column(Modifier.padding(horizontal = Spacing.s18)) {
                SurfaceCard(style = CardStyle.Error, modifier = Modifier.fillMaxWidth().testTag("library_unreachable_card")) {
                    val first = unreachable.first()
                    DisplayText("${first.name} is out of reach", maxLines = 2)
                    Spacer(Modifier.height(Spacing.s8))
                    Text(
                        (first.lastSeenAtMs?.let { "Last seen ${formatWhen(it)}. " } ?: "") + "Nothing on the share can be listed until the phone is back on that network.",
                        style = TextStyles.body, color = colors.body,
                    )
                    Spacer(Modifier.height(Spacing.s12))
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s8)) {
                        SecondaryButton(text = if (state.checkingReachability) "Checking…" else "Try again", enabled = !state.checkingReachability, onClick = viewModel::tryAgain, testTag = "library_try_again_button")
                        if (state.device.ready.isNotEmpty()) {
                            PrimaryButton(text = "Play the ${state.device.ready.size} on this device", onClick = { tab = LibraryTab.ON_DEVICE }, testTag = "library_go_device_button")
                        }
                    }
                }
                Spacer(Modifier.height(Spacing.s12))
            }
        }

        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            modifier = Modifier.fillMaxSize().testTag("library_grid"),
            contentPadding = PaddingValues(start = Spacing.s18, end = Spacing.s18, top = Spacing.s8, bottom = 120.dp),
            horizontalArrangement = Arrangement.spacedBy(Spacing.s8),
            verticalArrangement = Arrangement.spacedBy(Spacing.s18),
        ) {
            if (!state.loaded || (state.tiles.isEmpty() && state.scanning)) {
                items(9) { SkeletonTile() }
                return@LazyVerticalGrid
            }
            if (state.tiles.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    SurfaceCard(style = CardStyle.Empty, modifier = Modifier.fillMaxWidth().testTag("library_empty_card")) {
                        DisplayText(if (state.scannedOnce) "Nothing here" else "Not scanned yet")
                        Spacer(Modifier.height(Spacing.s8))
                        Text(
                            if (state.scannedOnce) "The scan found nothing playable here." else "Regolith reads the share once to know what is on it. Nothing is copied off it.",
                            style = TextStyles.body, color = colors.body,
                        )
                        if (!state.scannedOnce) {
                            Spacer(Modifier.height(Spacing.s18))
                            PrimaryButton(text = "Scan now", onClick = viewModel::scanAll, testTag = "library_scan_button", modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
                return@LazyVerticalGrid
            }
            if (state.scanning) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text("Still reading the share · more will arrive", style = TextStyles.metadata, color = colors.metadata, modifier = Modifier.testTag("library_scanning_line"))
                }
            }
            items(state.tiles, key = { it.testTag }) { tile ->
                when (tile) {
                    is LibraryTile.Collection -> MediaTile(
                        artwork = tile.artwork,
                        kind = ArtworkKind.POSTER,
                        title = tile.name,
                        meta = "${tile.fileCount} files",
                        count = tile.fileCount,
                        folder = true,
                        dimmed = unreachable.isNotEmpty(),
                        placeholderLabel = tile.name,
                        onClick = { onOpenCollection(tile.folderId) },
                        testTag = tile.testTag,
                    )
                    is LibraryTile.Title -> MediaTile(
                        artwork = tile.artwork,
                        kind = ArtworkKind.POSTER,
                        title = tile.name,
                        meta = tile.meta,
                        chip = tile.resolutionLabel.ifEmpty { null },
                        unwatched = tile.unwatched,
                        dimmed = unreachable.isNotEmpty(),
                        placeholderLabel = tile.name,
                        onClick = { onOpenTitle(tile.fileId) },
                        testTag = tile.testTag,
                    )
                }
            }
        }
    }

    if (state.sortSheetOpen) {
        ModalBottomSheet(
            onDismissRequest = { viewModel.openSortSheet(false) },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            shape = SheetShape,
            containerColor = colors.surface,
            contentColor = colors.ink,
            dragHandle = null,
        ) {
            Column(Modifier.padding(Spacing.s18).testTag("library_sort_sheet")) {
                Eyebrow("Sort by")
                Spacer(Modifier.height(Spacing.s8))
                LibrarySort.entries.forEach { sort ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clip(PillShape).clickable { viewModel.setSort(sort) }.padding(vertical = Spacing.s12).testTag("library_sort_${sort.name.lowercase()}"),
                    ) {
                        Text(sort.label, style = TextStyles.rowLabel, color = colors.ink, modifier = Modifier.weight(1f))
                        if (sort == state.sort) {
                            Icon(painterResource(LucideR.drawable.lucide_ic_check), contentDescription = "Selected", tint = colors.accent, modifier = Modifier.size(20.dp))
                        }
                    }
                }
                Spacer(Modifier.height(Spacing.s30))
            }
        }
    }
}

/** A resting poster: the geometry of what is coming. */
@Composable
private fun SkeletonTile() {
    Column {
        Skeleton(Modifier.fillMaxWidth().aspectRatio(2f / 3f), shape = CardShape)
        Spacer(Modifier.height(Spacing.s8))
        Skeleton(Modifier.width(72.dp).height(12.dp))
        Spacer(Modifier.height(Spacing.s4))
        Skeleton(Modifier.width(44.dp).height(10.dp))
    }
}

@Composable
private fun NoSource(onAddServer: () -> Unit) {
    val colors = RegolithTheme.colors
    Column(Modifier.padding(horizontal = Spacing.s18)) {
        Spacer(Modifier.height(Spacing.s30))
        SurfaceCard(style = CardStyle.Empty, modifier = Modifier.fillMaxWidth().testTag("library_empty_card")) {
            DisplayText("No source server")
            Spacer(Modifier.height(Spacing.s8))
            Text("Everything the scan understands shows up here as posters. Point Regolith at a share first.", style = TextStyles.body, color = colors.body)
            Spacer(Modifier.height(Spacing.s18))
            PrimaryButton(text = "Add source server", onClick = onAddServer, testTag = "library_add_server_button", modifier = Modifier.fillMaxWidth())
        }
    }
}

/**
 * "On this device" (design section 05): the storage line, files that
 * play first, then what is arriving, then the failures with the cause
 * named per row, three at a time with Show all at the foot so thirty
 * stalled transfers never push the working ones off screen.
 */
@Composable
private fun DeviceTab(
    state: DeviceUiState,
    onOpenTitle: (Long) -> Unit,
    onRetry: (Long) -> Unit,
    onCancel: (Long) -> Unit,
    onClearFailed: () -> Unit,
    onToggleShowAll: () -> Unit,
) {
    val colors = RegolithTheme.colors
    LazyColumn(Modifier.fillMaxSize().testTag("library_device_list"), contentPadding = PaddingValues(start = Spacing.s18, end = Spacing.s18, bottom = 120.dp)) {
        item {
            Text(
                "${formatBytes(state.usedBytes)} of ${formatBytes(state.totalBytes)} · plays with no network",
                style = TextStyles.metadata, color = colors.metadata, modifier = Modifier.padding(vertical = Spacing.s8).testTag("device_storage_line"),
            )
        }
        if (state.ready.isEmpty() && state.inFlight.isEmpty() && state.failed.isEmpty()) {
            item {
                Spacer(Modifier.height(Spacing.s12))
                SurfaceCard(style = CardStyle.Empty, modifier = Modifier.fillMaxWidth().testTag("library_device_empty")) {
                    DisplayText("Nothing on this device")
                    Spacer(Modifier.height(Spacing.s8))
                    Text("Open a title and choose \"Keep on this device\" to play it with no network.", style = TextStyles.body, color = colors.body)
                }
            }
            return@LazyColumn
        }
        if (state.ready.isNotEmpty()) {
            item { Eyebrow("Ready offline", Modifier.padding(top = Spacing.s12, bottom = Spacing.s4)) }
            listItems(state.ready, key = { "ready_${it.fileId}" }) { row ->
                DeviceRowView(row, onClick = { onOpenTitle(row.fileId) }, action = null, onAction = {})
            }
        }
        if (state.inFlight.isNotEmpty()) {
            item { Eyebrow("Arriving", Modifier.padding(top = Spacing.s18, bottom = Spacing.s4)) }
            listItems(state.inFlight, key = { "flight_${it.fileId}" }) { row ->
                DeviceRowView(row, onClick = { onOpenTitle(row.fileId) }, action = "Cancel", onAction = { onCancel(row.fileId) })
            }
        }
        if (state.failed.isNotEmpty()) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = Spacing.s18)) {
                    Eyebrow("Failed · ${state.failed.size}", Modifier.weight(1f))
                    TertiaryButton(text = "Clear all", onClick = onClearFailed, testTag = "device_clear_failed_button")
                }
            }
            val shown = if (state.showAllFailed) state.failed else state.failed.take(3)
            listItems(shown, key = { "failed_${it.fileId}" }) { row ->
                DeviceRowView(row, onClick = { onOpenTitle(row.fileId) }, action = "Try again", onAction = { onRetry(row.fileId) })
            }
            if (state.failed.size > 3) {
                item {
                    TertiaryButton(
                        text = if (state.showAllFailed) "Show fewer" else "Show all ${state.failed.size}",
                        onClick = onToggleShowAll, testTag = "device_show_all_button", modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

/** One transfer row: name, the cause or the meta, an optional action, a thin bar while arriving. */
@Composable
private fun DeviceRowView(row: DeviceRow, onClick: () -> Unit, action: String?, onAction: () -> Unit) {
    val colors = RegolithTheme.colors
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).testTag(row.testTag)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = Spacing.s12)) {
            Column(Modifier.weight(1f)) {
                Text(row.name, style = TextStyles.rowLabel, color = colors.ink, maxLines = 1)
                Text(
                    when (row.status) {
                        TransferStatus.DONE -> row.meta
                        TransferStatus.QUEUED -> "Queued · ${formatBytes(row.totalBytes)}"
                        TransferStatus.RUNNING -> "${formatBytes(row.bytesDone)} of ${formatBytes(row.totalBytes)}"
                        TransferStatus.PAUSED -> "Waiting for the share · paused at ${formatBytes(row.bytesDone)} of ${formatBytes(row.totalBytes)}, resumes on its own"
                        TransferStatus.FAILED -> when (row.cause) {
                            TransferCause.NO_ROOM -> "No room · ${formatBytes(row.causeBytes ?: 0)} needed"
                            TransferCause.SHARE_DROPPED -> "The share dropped · ${formatBytes(row.bytesDone)}/${formatBytes(row.totalBytes)}"
                            else -> "The copy failed"
                        }
                    },
                    style = TextStyles.metadata, color = colors.metadata, maxLines = 2,
                )
            }
            if (action != null) {
                Spacer(Modifier.width(Spacing.s8))
                SecondaryButton(text = action, onClick = onAction, testTag = "${row.testTag}_action")
            }
        }
        if (row.status == TransferStatus.RUNNING || row.status == TransferStatus.PAUSED) {
            LinearProgressIndicator(progress = { row.fraction }, color = colors.ink, trackColor = colors.hairline, modifier = Modifier.fillMaxWidth())
        }
        HorizontalDivider(color = colors.hairline, thickness = 1.dp)
    }
}
