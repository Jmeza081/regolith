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
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = RegolithTheme.colors
    var tab by remember { mutableStateOf(LibraryTab.NETWORK) }

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
            Column(Modifier.padding(horizontal = Spacing.s18)) {
                Spacer(Modifier.height(Spacing.s18))
                SurfaceCard(style = CardStyle.Empty, modifier = Modifier.fillMaxWidth().testTag("library_device_empty")) {
                    DisplayText("Nothing on this device")
                    Spacer(Modifier.height(Spacing.s8))
                    Text("Downloads for playing with no network arrive in a later phase.", style = TextStyles.body, color = colors.body)
                }
            }
            return
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
