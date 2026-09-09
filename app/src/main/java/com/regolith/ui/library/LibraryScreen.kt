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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.regolith.R
import com.regolith.domain.artwork.ArtworkKind
import com.regolith.domain.artwork.ArtworkOwner
import com.regolith.domain.artwork.ArtworkRequest
import com.regolith.domain.library.LibrarySort
import com.regolith.domain.transfer.TransferCause
import com.regolith.domain.transfer.TransferStatus
import com.regolith.ui.components.ArtworkImage
import com.regolith.ui.components.CardStyle
import com.regolith.ui.components.DisplayText
import com.regolith.ui.components.Eyebrow
import com.regolith.ui.components.MediaTile
import com.regolith.ui.components.PrimaryButton
import com.regolith.ui.components.Segment
import com.regolith.ui.components.SegmentedTabs
import com.regolith.ui.components.Skeleton
import com.regolith.ui.components.SurfaceCard
import com.regolith.ui.components.TopBar
import com.regolith.ui.components.TopBarAction
import com.regolith.ui.theme.RegolithTheme
import com.regolith.ui.theme.SheetShape
import com.regolith.ui.theme.Spacing
import com.regolith.ui.theme.TextStyles
import com.regolith.ui.theme.TileShape
import com.regolith.ui.util.formatBytes
import com.regolith.ui.util.formatWhen

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
    modifier: Modifier = Modifier,
    startOnDevice: Boolean = false,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = RegolithTheme.colors
    var tab by remember { mutableStateOf(if (startOnDevice) LibraryTab.ON_DEVICE else LibraryTab.NETWORK) }
    val unreachable = state.unreachable
    val readyCount = state.device.ready.size

    Column(modifier.fillMaxSize().testTag("library_screen")) {
        val subtitle = when {
            tab == LibraryTab.ON_DEVICE -> "${formatBytes(state.device.usedBytes)} of ${formatBytes(state.device.totalBytes)} · plays with no network"
            unreachable.isNotEmpty() -> "${unreachable.first().name} unreachable · $readyCount file${if (readyCount == 1) "" else "s"} playable here"
            else -> state.meta
        }
        TopBar(
            title = if (onBack == null) "Media" else state.title,
            onBack = onBack,
            subtitle = subtitle,
            subtitleMuted = tab == LibraryTab.ON_DEVICE || unreachable.isNotEmpty(),
            actions = listOf(
                TopBarAction(R.drawable.rg_ic_search_alt, "Search", "library_search_button", onSearch),
                TopBarAction(R.drawable.rg_ic_sort, "Sort", "library_sort_button") { viewModel.openSortSheet(true) },
            ),
        )

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
                modifier = Modifier.padding(start = Spacing.s18, end = Spacing.s18, bottom = Spacing.s18),
            )
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

        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            modifier = Modifier.fillMaxSize().testTag("library_grid"),
            contentPadding = PaddingValues(start = Spacing.s18, end = Spacing.s18, bottom = 112.dp),
            horizontalArrangement = Arrangement.spacedBy(Spacing.s8),
            verticalArrangement = Arrangement.spacedBy(Spacing.s8),
        ) {
            // Only the Network tab is affected by a share going away (design:
            // "the message lives there rather than over files that play fine").
            if (unreachable.isNotEmpty() && onBack == null) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    OutOfReach(
                        server = unreachable.first(),
                        checking = state.checkingReachability,
                        readyCount = readyCount,
                        paused = state.device.inFlight.filter { it.status == TransferStatus.PAUSED },
                        onTryAgain = viewModel::tryAgain,
                        onGoDevice = { tab = LibraryTab.ON_DEVICE },
                    )
                }
            }
            if (!state.loaded || (state.tiles.isEmpty() && state.scanning)) {
                items(9) { SkeletonTile() }
                return@LazyVerticalGrid
            }
            if (state.tiles.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    SurfaceCard(style = CardStyle.Empty, modifier = Modifier.fillMaxWidth().testTag("library_empty_card")) {
                        DisplayText(if (state.scannedOnce) "Nothing here" else "Not scanned yet", style = TextStyles.emptyTitle)
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
                    Text("Still reading the share · more will arrive", style = TextStyles.meta, color = colors.metadata, modifier = Modifier.testTag("library_scanning_line"))
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
                        resolution = tile.resolutionLabel.ifEmpty { null },
                        dimmed = unreachable.isNotEmpty(),
                        onClick = { onOpenCollection(tile.folderId) },
                        testTag = tile.testTag,
                    )
                    is LibraryTile.Title -> MediaTile(
                        artwork = tile.artwork,
                        kind = ArtworkKind.POSTER,
                        title = tile.name,
                        meta = tile.meta,
                        resolution = tile.resolutionLabel.ifEmpty { null },
                        unwatched = tile.unwatched,
                        matched = tile.matched,
                        fallbackLabel = tile.fileName,
                        progress = tile.progress,
                        dimmed = unreachable.isNotEmpty(),
                        onClick = { onOpenTitle(tile.fileId) },
                        testTag = tile.testTag,
                    )
                }
            }
        }
    }

    if (state.sortSheetOpen) {
        SortSheet(selected = state.sort, onSelect = viewModel::setSort, onDismiss = { viewModel.openSortSheet(false) })
    }
}

/**
 * The sort sheet (design: "Sheets step up to #0F0F0F with a 22px top
 * radius. The check is the only red on the screen."): a 38×4 handle,
 * "SORT BY" in Michroma 14, five 48dp rows at 500 15/20.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SortSheet(selected: LibrarySort, onSelect: (LibrarySort) -> Unit, onDismiss: () -> Unit) {
    val colors = RegolithTheme.colors
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = SheetShape,
        containerColor = colors.surface,
        contentColor = colors.ink,
        dragHandle = null,
    ) {
        Column(Modifier.padding(start = Spacing.s18, end = Spacing.s18, top = Spacing.s12).navigationBarsPadding().testTag("library_sort_sheet")) {
            Box(Modifier.align(Alignment.CenterHorizontally).size(38.dp, 4.dp).background(colors.raised, RoundedCornerShape(2.dp)))
            Spacer(Modifier.height(Spacing.s12))
            DisplayText("Sort by", style = TextStyles.dialogTitle.copy(fontSize = 14.sp, lineHeight = 19.6.sp))
            Spacer(Modifier.height(Spacing.s4))
            LibrarySort.entries.forEach { sort ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp)
                        .clickable(interactionSource = null, indication = null) { onSelect(sort) }
                        .testTag("library_sort_${sort.name.lowercase()}"),
                ) {
                    Text(sort.label, style = TextStyles.settingLabel.copy(lineHeight = 20.sp), color = if (sort == selected) colors.ink else colors.inkSoft, modifier = Modifier.weight(1f))
                    if (sort == selected) {
                        Icon(painterResource(R.drawable.rg_ic_check), contentDescription = "Selected", tint = colors.accent, modifier = Modifier.size(18.dp))
                    }
                }
            }
            Spacer(Modifier.height(Spacing.s12))
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
                Icon(painterResource(R.drawable.rg_ic_wifi_off), contentDescription = null, tint = colors.body, modifier = Modifier.size(19.dp))
                Spacer(Modifier.width(Spacing.s12))
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.s8)) {
                    Text("${server.name} is out of reach", style = TextStyles.rowLabelMedium.copy(lineHeight = 20.sp), color = colors.inkSoft)
                    Text(
                        (server.lastSeenAtMs?.let { "Last seen ${formatWhen(it)}. " } ?: "") + "Nothing on the share can be listed until the phone is back on that network.",
                        style = TextStyles.settingMeta.copy(lineHeight = 18.sp), color = colors.body,
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
                        Box(Modifier.width(60.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp)).alpha(0.4f)) {
                            ArtworkImage(ArtworkRequest(ArtworkOwner.File(row.fileId), ArtworkKind.THUMB), Modifier.fillMaxSize(), fallbackLabel = row.name)
                        }
                        Spacer(Modifier.width(Spacing.s12))
                        Text(
                            "${row.name} · paused at ${formatBytes(row.bytesDone)} of ${formatBytes(row.totalBytes)}, resumes on its own",
                            style = TextStyles.settingMeta.copy(lineHeight = 18.sp), color = colors.body,
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
    Box(Modifier.fillMaxSize().padding(start = Spacing.s18, end = Spacing.s18, bottom = 112.dp), contentAlignment = Alignment.Center) {
        Column(
            Modifier.fillMaxWidth().background(Color(0xFF050505), RoundedCornerShape(20.dp))
                .dashedBorder(colors.raised, RoundedCornerShape(20.dp))
                .padding(horizontal = Spacing.s18, vertical = Spacing.s30)
                .testTag("library_empty_card"),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.s18),
        ) {
            Icon(painterResource(R.drawable.rg_ic_server), contentDescription = null, tint = colors.body, modifier = Modifier.size(18.dp))
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
) {
    val colors = RegolithTheme.colors
    LazyColumn(
        Modifier.fillMaxSize().testTag("library_device_list"),
        contentPadding = PaddingValues(start = Spacing.s18, end = Spacing.s18, bottom = 126.dp),
        verticalArrangement = Arrangement.spacedBy(Spacing.s18),
    ) {
        if (state.ready.isEmpty() && state.inFlight.isEmpty() && state.failed.isEmpty()) {
            item {
                SurfaceCard(style = CardStyle.Empty, modifier = Modifier.fillMaxWidth().testTag("library_device_empty")) {
                    DisplayText("Nothing on this device", style = TextStyles.emptyTitle)
                    Spacer(Modifier.height(Spacing.s8))
                    Text("Open a title and choose \"Keep on this device\" to play it with no network.", style = TextStyles.body, color = colors.body)
                }
            }
            return@LazyColumn
        }
        if (state.ready.isNotEmpty()) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.s8)) {
                    Eyebrow("Ready offline", muted = true)
                    SurfaceCard(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = Spacing.s12)) {
                        state.ready.forEach { row -> DeviceRowView(row, minHeight = 64.dp, onClick = { onOpenTitle(row.fileId) }, action = null, onAction = {}) }
                    }
                }
            }
        }
        if (state.inFlight.isNotEmpty()) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.s8)) {
                    Eyebrow("Arriving", muted = true)
                    SurfaceCard(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = Spacing.s12)) {
                        state.inFlight.forEach { row -> DeviceRowView(row, minHeight = 72.dp, onClick = { onOpenTitle(row.fileId) }, action = "Cancel", onAction = { onCancel(row.fileId) }) }
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
                            "Clear all", style = TextStyles.buttonSmall.copy(fontSize = 12.sp), color = colors.accent,
                            modifier = Modifier.clickable(interactionSource = null, indication = null, onClick = onClearFailed).testTag("device_clear_failed_button"),
                        )
                    }
                    SurfaceCard(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = Spacing.s12)) {
                        val shown = if (state.showAllFailed) state.failed else state.failed.take(3)
                        shown.forEach { row -> DeviceRowView(row, minHeight = 72.dp, onClick = { onOpenTitle(row.fileId) }, action = "Try again", onAction = { onRetry(row.fileId) }, dimThumb = true) }
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
private fun DeviceRowView(row: DeviceRow, minHeight: androidx.compose.ui.unit.Dp, onClick: () -> Unit, action: String?, onAction: () -> Unit, dimThumb: Boolean = false) {
    val colors = RegolithTheme.colors
    Row(
        Modifier.fillMaxWidth().defaultMinSize(minHeight = minHeight).clickable(interactionSource = null, indication = null, onClick = onClick).padding(vertical = Spacing.s4).testTag(row.testTag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(60.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(9.dp)).alpha(if (dimThumb) 0.45f else 1f)) {
            ArtworkImage(ArtworkRequest(ArtworkOwner.File(row.fileId), ArtworkKind.THUMB), Modifier.fillMaxSize(), fallbackLabel = row.name)
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
        if (action != null) {
            Spacer(Modifier.width(Spacing.s12))
            Text(
                action, style = TextStyles.buttonSmall.copy(fontSize = 12.sp), color = colors.ink,
                modifier = Modifier.clickable(interactionSource = null, indication = null, onClick = onAction).testTag("${row.testTag}_action"),
            )
        }
    }
}
