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
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.animation.animateColorAsState
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.regolith.R
import com.composables.icons.lucide.R as LucideR
import com.regolith.domain.artwork.ArtworkKind
import com.regolith.domain.artwork.ArtworkOwner
import com.regolith.domain.artwork.ArtworkRequest
import com.regolith.domain.library.LibraryOrder
import com.regolith.domain.playback.Reel
import com.regolith.domain.playback.Story
import com.regolith.domain.playback.ReelClip
import com.regolith.domain.playback.ReelMoment
import com.regolith.domain.library.LibrarySort
import com.regolith.domain.library.MomentSort
import com.regolith.domain.library.SortChoice
import com.regolith.domain.library.SortDirection
import com.regolith.domain.transfer.TransferCause
import com.regolith.domain.transfer.TransferStatus
import com.regolith.ui.components.LocalNavPillInsets
import com.regolith.ui.components.LocalSelectionChrome
import com.regolith.ui.components.SelectionChromeState
import com.regolith.ui.components.SelectionVerb
import com.regolith.ui.components.FileActionsHost
import com.regolith.ui.components.PictureVerbs
import com.regolith.ui.components.UploadActionsHost
import com.regolith.ui.components.UploadSectionView
import com.regolith.ui.components.UploadSource
import com.regolith.ui.util.UploadActionsState
import com.regolith.ui.components.FileSelectionChrome
import com.regolith.ui.components.ArtworkImage
import com.regolith.ui.components.DisplayText
import com.regolith.ui.components.EmptyAction
import com.regolith.ui.components.EmptyState
import com.regolith.ui.components.Ghost
import com.regolith.ui.components.Eyebrow
import com.regolith.ui.components.EyebrowAction
import com.regolith.ui.components.MediaTile
import com.regolith.ui.components.ThumbCells
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
import com.regolith.ui.util.SelectionUiState
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import com.regolith.ui.theme.PillShape
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.regolith.ui.theme.DialogMaxWidth
import com.regolith.ui.theme.DialogShape
import com.regolith.ui.components.DestructiveButton
import com.regolith.ui.components.SecondaryButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items as rowItems // the grid import below owns `items`
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.regolith.domain.media.PhoneAccess
import com.regolith.ui.components.FilterChip
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.zIndex
import com.regolith.ui.components.WallPinchPill
import com.regolith.ui.components.rememberWallPinch
import com.regolith.ui.components.wallPinch
import kotlin.math.roundToInt
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import com.regolith.domain.display.PostersPerRow
import com.regolith.ui.adaptive.LocalWindowShape
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.runtime.SideEffect
import com.regolith.domain.display.PicturesAcross
import com.regolith.domain.library.PictureSort
import com.regolith.ui.components.PictureMosaicGap
import com.regolith.ui.components.PictureMosaicTile
import com.regolith.ui.components.picturesFitting
import com.regolith.ui.lightbox.LocalPictureFocus
import com.regolith.ui.util.formatCount
import com.regolith.ui.util.formatDate

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
    /** A collection tile: its wall or its profile, opening with the chip lit here ([LibraryFilter]). */
    onOpenCollection: (folderId: Long, filter: LibraryFilter) -> Unit,
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
    /**
     * Called just before a picker opens (adding videos or a poster to a
     * collection). The picker is another app's screen, so Regolith goes to
     * the background; this tells the app lock the trip back is one it sent
     * the user on.
     */
    onSendingAway: () -> Unit = {},
    /**
     * Play a video from a time: a moment on a collection profile's Moments
     * tab opens the player there, as a point of interest does in Search.
     */
    onPlayAt: (fileId: Long, startMs: Long) -> Unit = { _, _ -> },
    /** A profile's Play moments: its moments as a reel, called by the collection's [title]. */
    onPlayMoments: ((title: String, clips: List<ReelClip>) -> Unit)? = null,
    /**
     * A picture: the lightbox over the pictures of [folderId], starting at
     * [pictureId], in the order they were showing: the Images tab's, or the
     * wall's own ([onWall]) for a picture lying loose beside albums.
     */
    onOpenPicture: (folderId: Long, pictureId: Long, onWall: Boolean) -> Unit = { _, _, _ -> },
    /**
     * Set as poster for [folderId]: a picture on the share ([pictureId], the
     * selection's Poster) or one picked on the phone ([uri], the upload
     * sheet's Collection poster).
     */
    onSetPoster: (folderId: Long, pictureId: Long?, uri: String?) -> Unit = { _, _, _ -> },
    /** A profile's Play pictures: [folderId]'s pictures as a story, in the tab's order or [shuffle]d. */
    onPlayPictures: ((folderId: Long, shuffle: Boolean) -> Unit)? = null,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val uploadActions = viewModel.uploadActions
    val uploads by (uploadActions?.state ?: NoUploads).collectAsStateWithLifecycle()
    val colors = RegolithTheme.colors
    var tab by remember { mutableStateOf(if (startOnDevice) LibraryTab.ON_DEVICE else LibraryTab.NETWORK) }
    var playAllOpen by remember { mutableStateOf(false) }
    val unreachable = state.unreachable
    val readyCount = state.device.ready.size
    // Downloads that play plus the phone's own videos: everything the device
    // tab can start with no network.
    val deviceCount = state.device.playableCount

    // With no server left but something on the phone, open on the tab that
    // has something on it. Keyed on the CONDITION, not on the state, so it
    // fires once and never fights a later tap on Network.
    val onlyDeviceHasContent = state.loaded && state.noSource && deviceCount > 0
    LaunchedEffect(onlyDeviceHasContent) {
        if (onlyDeviceHasContent) tab = LibraryTab.ON_DEVICE
    }

    val selection = state.selection
    val selecting = selection != null

    // A leaf collection opens as its profile (CollectionProfile): the poster
    // lighting the page, its stats, and Videos / Moments tabs over the wall.
    // Network tab only: the device tab is a different page sharing this screen.
    val profile = state.profile.takeIf { onBack != null && tab == LibraryTab.NETWORK }
    // It opens on the tab the chip it was opened from names (LibraryFilter);
    // a tab the collection does not have gives way to its first.
    var chosenTab by rememberSaveable { mutableStateOf(state.filter.profileTab()) }
    val profileTab = profile?.tabs?.let { tabs -> if (chosenTab in tabs) chosenTab else tabs.firstOrNull() } ?: chosenTab
    // An album's pictures, a mosaic in place of the wall (PictureMosaic.kt).
    val showPictures = profile != null && profileTab == ProfileTab.IMAGES
    // The wall's scroll positions. Up here, not beside the wall, because a
    // profile's top bar floats over the wall and fills in once the header
    // has scrolled up behind it, which it reads from these.
    val gridState = rememberLazyGridState()
    val listState = rememberLazyListState()
    val mosaicState = rememberLazyStaggeredGridState()
    // Switching between the Videos grid and the Images mosaic swaps the lazy
    // container under the header: the new one starts where the old one was,
    // so the tabs stay where the finger left them. Applied at the next
    // layout (requestScrollToItem), so no frame shows the top of the page.
    val wasShowingPictures = remember { booleanArrayOf(showPictures) }
    SideEffect {
        if (wasShowingPictures[0] == showPictures) return@SideEffect
        wasShowingPictures[0] = showPictures
        if (state.viewMode != ViewMode.GRID) return@SideEffect
        val (index, offset) = if (showPictures) gridState.firstVisibleItemIndex to gridState.firstVisibleItemScrollOffset else mosaicState.firstVisibleItemIndex to mosaicState.firstVisibleItemScrollOffset
        // Past the tabs, the other list has nothing at that index: it starts with its tabs at the top.
        val (toIndex, toOffset) = if (index <= 1) index to offset else 1 to 0
        if (showPictures) mosaicState.requestScrollToItem(toIndex, toOffset) else gridState.requestScrollToItem(toIndex, toOffset)
    }
    var profileHeaderPx by remember { mutableIntStateOf(0) }

    // A pinch on the wall steps how many posters sit across it (WallPinch),
    // from what the wall shows to as many as fit, and keeps the answer as
    // Settings › Display › Posters per row. The inner display only, and only
    // with the whole width: beside a page the wall keeps its posters' size
    // rather than a count, so "N across" would not be what you were looking at.
    val wallWidth = wallFullWidth()
    val pinch = rememberWallPinch(
        shown = wallColumns(wallWidth, wide = true, state.postersPerRow.count, wallWidth),
        most = wallFitting(wallWidth),
        onStep = viewModel::setPostersPerRow,
    )
    val pinchable = LocalWindowShape.current.wide && selectedFileId == null
    // An album's mosaic pinches on every screen, phone included, in a range
    // of its own and kept apart from the posters (PicturesAcross).
    val mosaicScreen = PicturesAcross.of(LocalWindowShape.current.wide)
    val mosaicFit = picturesFitting(wallWidth).coerceAtLeast(mosaicScreen.fewest)
    val mosaicAcross = mosaicScreen.clamp(if (mosaicScreen == PicturesAcross.WIDE) state.picturesAcrossWide else state.picturesAcrossPhone).coerceAtMost(mosaicFit)
    val mosaicPinch = rememberWallPinch(
        shown = mosaicAcross,
        most = mosaicFit,
        onStep = { viewModel.setPicturesAcross(mosaicScreen, it) },
        fewest = mosaicScreen.fewest,
        top = mosaicScreen.most,
    )
    // Where the poster wall starts on screen, for the pill to sit just inside it.
    var wallTopPx by remember { mutableIntStateOf(0) }

    // Deleting the collection you are standing in leaves nothing to draw, so
    // the wall leaves with it, as Browse's folders do. Only a success can
    // pop it; a failure leaves the wall, and its message, where they were.
    LaunchedEffect(state.gone) { if (state.gone) onBack?.invoke() }

    // Each selection belongs to one tab, and the two mean opposite things —
    // download these, delete these. Switching tabs ends whichever one you
    // walked away from, so the bar never counts things the tab cannot show.
    LaunchedEffect(tab) {
        if (tab == LibraryTab.ON_DEVICE) viewModel.cancelSelection() else viewModel.cancelDeviceSelection()
    }
    // Every time the device tab is in view — including coming back from the
    // system permission sheet or Android's settings — re-read the phone:
    // the permission can change and videos can arrive while we are away,
    // and nothing tells an app either. `repeatOnLifecycle` is the Compose
    // way to say "on every resume", like a visibilitychange listener.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(tab, lifecycle) {
        if (tab == LibraryTab.ON_DEVICE) lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { viewModel.onDeviceShown() }
    }
    // The system's own "Allow Regolith to access videos?" sheet. Both
    // permissions in one request: that is what makes it offer "Select
    // videos" beside "Allow all". The answer arrives here; the ViewModel
    // re-reads what was granted rather than trusting this map.
    var askedPhone by rememberSaveable { mutableStateOf(false) }
    val askPhone = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        askedPhone = true
        viewModel.onPhoneAccessAnswered()
    }
    val context = androidx.compose.ui.platform.LocalContext.current
    val requestPhone = {
        askPhone.launch(arrayOf(android.Manifest.permission.READ_MEDIA_VIDEO, android.Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED))
    }
    // After a refusal Android stops showing the sheet at all, so the only
    // way back is the app's page in Android's settings.
    val openAppSettings = {
        context.startActivity(
            android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", context.packageName, null)),
        )
    }
    // The one Sort button sorts whatever list is on screen, and the sheet
    // offers that list's own choices: the device tab and a profile's Moments
    // tab each keep an order of their own. Up here, before the page, because
    // the device tab and the no-server page return early, and a sheet below
    // them was never drawn: tapping Sort there did nothing until you went
    // back to the Network tab, where it opened by itself.
    if (state.sortSheetOpen) {
        val close = { viewModel.openSortSheet(false) }
        when {
            tab == LibraryTab.ON_DEVICE ->
                SortSheet(LibrarySort.entries, state.device.order.sort, state.device.order.direction, viewModel::pickDeviceSort, close)
            profile != null && profileTab == ProfileTab.MOMENTS ->
                SortSheet(MomentSort.entries, state.momentOrder.sort, state.momentOrder.direction, viewModel::pickMomentSort, close)
            showPictures ->
                SortSheet(PictureSort.entries, state.pictureOrder.sort, state.pictureOrder.direction, viewModel::pickPictureSort, close)
            else -> SortSheet(LibrarySort.entries, state.order.sort, state.order.direction, viewModel::pickSort, close)
        }
    }

    // No BackHandler: back walks out of the collection and the selection
    // comes with it, so a pick can span a wall and the folders under it.
    // Leaving selection is the X above or Cancel below.

    Box(modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().testTag("library_screen")) {
        val subtitle = when {
            tab == LibraryTab.ON_DEVICE && state.device.phoneCount > 0 -> listOfNotNull(
                when (readyCount) { 0 -> null; 1 -> "1 download"; else -> "$readyCount downloads" },
                "${state.device.phoneCount} on this phone",
            ).joinToString(" · ")
            tab == LibraryTab.ON_DEVICE -> "${formatBytes(state.device.usedBytes)} of ${formatBytes(state.device.totalBytes)} · plays with no network"
            unreachable.isNotEmpty() -> "${unreachable.first().name} unreachable · $readyCount file${if (readyCount == 1) "" else "s"} playable here"
            // Inside a collection the line counts what the lit chip shows.
            onBack != null && state.filter != LibraryFilter.VIDEOS -> wallCount(state.tiles, state.filter)
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
        } else if (selecting && profile == null) {
            SelectionBar(selection!!.itemCount, onBack, viewModel::selectAllHere, viewModel::cancelSelection)
        } else if (profile == null) {
            TopBar(
                title = if (onBack == null) "Media" else state.title,
                onBack = onBack,
                subtitle = subtitle,
                subtitleMuted = tab == LibraryTab.ON_DEVICE || unreachable.isNotEmpty(),
                actions = listOfNotNull(
                    TopBarAction(R.drawable.rg_ic_search_alt, "Search", "library_search_button", onSearch),
                    TopBarAction(R.drawable.rg_ic_sort, "Sort", "library_sort_button") { viewModel.openSortSheet(true) },
                    // Videos and a poster into the collection on screen, only
                    // where a share is behind it: not the first wall, not the demo.
                    TopBarAction(R.drawable.rg_ic_upload, "Add to this collection", "library_upload_button") { uploadActions?.openSheet() }
                        .takeIf { uploads.canUpload && tab == LibraryTab.NETWORK },
                    // One button, two lists: it toggles whichever tab you are on.
                    if (tab == LibraryTab.ON_DEVICE) {
                        viewModeAction(state.device.viewMode, "library_view_mode_button", viewModel::toggleDeviceViewMode)
                    } else {
                        viewModeAction(state.viewMode, "library_view_mode_button", viewModel::toggleViewMode)
                    },
                ),
            )
        }

        // The tabs stay even with no server at all. There used to be a
        // whole-screen "add a server" here for that case, but the device tab
        // is never empty of purpose any more: it is where the phone's own
        // videos live, and where the copies you kept wait after the last
        // server goes. The Network tab carries the invitation instead.

        if (onBack == null) {
            SegmentedTabs(
                segments = listOf(
                    Segment("Network", "library_tab_network"),
                    Segment("On this device", "library_tab_device", count = deviceCount.takeIf { it > 0 }),
                ),
                selected = if (tab == LibraryTab.NETWORK) 0 else 1,
                onSelect = { tab = if (it == 0) LibraryTab.NETWORK else LibraryTab.ON_DEVICE },
                // The top bar's subtitle ends 8dp above this; on its own that read as
                // one block of text with a control stuck to it.
                modifier = Modifier.padding(start = Spacing.s18, end = Spacing.s18, top = Spacing.s12, bottom = Spacing.s18),
            )
        }

        // What the wall is OF (LibraryFilter): videos, the moments in them, or
        // pictures. Only where there is a choice to make, and never on a
        // profile, whose own tabs say the same thing.
        if (tab == LibraryTab.NETWORK && profile == null && (state.filtersWithTiles.size > 1 || state.filter != LibraryFilter.VIDEOS)) {
            FilterChips(
                state.filter, viewModel::setFilter,
                Modifier.padding(start = Spacing.s18, end = Spacing.s18, bottom = Spacing.s12, top = if (onBack == null) 0.dp else Spacing.s8),
            )
        }

        // The collection's own CTA. Titles only: a Collection tile is a folder
        // of folders and has no single file to start with, so a wall of them
        // has nothing to play in order.
        val playable = state.tiles.filterIsInstance<LibraryTile.Title>()
        if (onPlayAll != null && tab == LibraryTab.NETWORK && playable.isNotEmpty() && profile == null && state.filter == LibraryFilter.VIDEOS) {
            PlayAllButton(
                onClick = { playAllOpen = true },
                modifier = Modifier.padding(start = Spacing.s18, end = Spacing.s18, bottom = Spacing.s12),
            )
        }

        if (tab == LibraryTab.ON_DEVICE) {
            // Its own Box so the remove bar floats over the list rather than
            // stacking under it; this branch returns before the wall's own.
            // Its tiles follow the same posters across, so the same pinch.
            Box(Modifier.fillMaxSize().wallPinch(pinch.takeIf { pinchable && state.device.viewMode == ViewMode.GRID })) {
                WallPinchPill(pinch, Modifier.align(Alignment.TopCenter).padding(top = Spacing.s8).zIndex(1f))
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
                    onFilter = viewModel::setDeviceFilter,
                    askedPhone = askedPhone,
                    onAskPhone = requestPhone,
                    onOpenAppSettings = openAppSettings,
                    postersPerRow = state.postersPerRow.count,
                )
                val devicePicked = state.device.picked
                if (devicePicked != null) {
                    // The same chrome as every other selection in the app: the
                    // pill becomes the toolbar, the tier above it carries the
                    // count. Removing copies is a different act from
                    // downloading them, but it is the same GESTURE, so it must
                    // not look like a different feature.
                    val n = devicePicked.size
                    val deviceChrome = LocalSelectionChrome.current
                    DisposableEffect(devicePicked) {
                        deviceChrome.show(
                            SelectionChromeState(
                                verbs = listOf(
                                    SelectionVerb(
                                        label = "Remove",
                                        icon = R.drawable.rg_ic_trash,
                                        onClick = viewModel::askRemovePicked,
                                        testTag = "device_select_remove",
                                        enabled = n > 0,
                                        destructive = true,
                                    ),
                                ),
                                onCancel = viewModel::cancelDeviceSelection,
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
                            ),
                        )
                        onDispose { deviceChrome.clear() }
                    }
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

        // A new sort moves the wall's scroll positions (held above).
        // Tiles are keyed, and a keyed list keeps the item that was at the
        // top in view when the order changes. After a re-sort that item can
        // be near the END, so the wall landed at the bottom. A new order is
        // a new list to read from the start, so glide back to the top.
        // `lastOrder` skips the first composition, which is also what keeps
        // a restored scroll position when you come back from a title.
        // The Moments tab's order moves the same lists, so it counts as a new order too.
        val orders = state.order to state.momentOrder
        var lastOrder by remember { mutableStateOf(orders) }
        LaunchedEffect(orders) {
            if (orders == lastOrder) return@LaunchedEffect
            lastOrder = orders
            if (state.viewMode == ViewMode.GRID) gridState.animateScrollToItem(0) else listState.animateScrollToItem(0)
        }

        // Both layouts draw the same leading blocks and the same tiles; only
        // the container differs, so the decisions are made once, here.
        val showUnreachable = unreachable.isNotEmpty() && onBack == null
        val showSkeletons = !state.loaded || (state.tiles.isEmpty() && state.scanning)
        // Files on their way are what this collection is about to hold: no
        // "nothing here" above them.
        val showEmpty = state.loaded && state.tiles.isEmpty() && !state.scanning && uploads.section == null
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
        // Network with no server: the invitation belongs on this tab, not over
        // the whole screen, because the other tab still has files on it.
        if (state.loaded && state.noSource) {
            NoSource(onAddServer)
            return
        }
        val emptyBlock = @Composable { EmptyWall(scannedOnce = state.scannedOnce, rows = state.viewMode != ViewMode.GRID, onScan = viewModel::scanAll, filter = state.filter) }

        // A page opening beside the wall reflows it — five across become two
        // on the inner display — and a keyed grid keeps its FIRST visible
        // tile where it was, which can push the one just tapped off the
        // bottom. For as long as the page takes to arrive, each new width
        // gets one look, and a tapped tile left out of view is brought up to
        // the top, level with the page's art. Rows need none of this: a row
        // is the same height at any width, so it stays where it was.
        val leadingItems = (if (profile != null) 2 else 0) +
            (if (showUnreachable) 1 else 0) + (if (uploads.section != null) 1 else 0) + (if (showScanLine) 1 else 0)
        // In the order the wall shows their videos, so sorting one sorts both.
        val moments = remember(profile?.moments, state.tiles, state.momentOrder) { profile?.moments.orEmpty().inOrder(state.momentOrder, state.tiles) }
        val showMoments = profile != null && profileTab == ProfileTab.MOMENTS
        // The reel those moments make, in the tab's order: ten seconds of each,
        // or up to the next mark in the same video (Reel.clips).
        val reelClips = remember(moments, state.tiles) {
            val lengths = state.tiles.filterIsInstance<LibraryTile.Title>().associate { it.fileId to it.durationMs }
            Reel.clips(moments.map { ReelMoment(it.fileId, it.startMs, it.title, it.videoName) }, lengths)
        }
        val momentsPlay = if (showMoments && reelClips.isNotEmpty() && onPlayMoments != null) {
            TabPlay(
                noun = "moments",
                length = Reel.roughLength(Reel.lengthMs(reelClips)),
                onPlay = { onPlayMoments(state.title, reelClips) },
                onShuffle = { onPlayMoments(state.title, reelClips.shuffled()) },
            )
        } else {
            null
        }
        // The Images tab plays its pictures as a story, at the pace Settings sets.
        val albumId = viewModel.folderId
        val picturesPlay = if (showPictures && profile?.pictures.orEmpty().isNotEmpty() && onPlayPictures != null && albumId != null) {
            TabPlay(
                noun = "pictures",
                length = Reel.roughLength(Story.lengthMs(profile?.pictures.orEmpty().size, state.storyPace)),
                onPlay = { onPlayPictures(albumId, false) },
                onShuffle = { onPlayPictures(albumId, true) },
            )
        } else {
            null
        }
        // The profile's header and tabs, the first two items of either layout.
        val playableIds = { state.tiles.filterIsInstance<LibraryTile.Title>().map { it.fileId } }
        val profileHeader = @Composable { p: CollectionProfile ->
            ProfileHeader(
                profile = p,
                name = state.title,
                // An album of pictures alone has no videos to play.
                onPlay = if (p.videoCount > 0) ({ onPlayAll?.invoke(playableIds(), false) }) else null,
                onShuffle = if (p.videoCount > 0) ({ onPlayAll?.invoke(playableIds(), true) }) else null,
                onAdd = if (uploads.canUpload) ({ uploadActions?.openSheet() }) else null,
                modifier = Modifier.onSizeChanged { profileHeaderPx = it.height },
                tab = momentsPlay ?: picturesPlay,
            )
        }
        val profileTabs = @Composable { p: CollectionProfile ->
            // An album of pictures alone has one thing to show, and no tabs.
            if (p.tabs.size > 1) ProfileTabs(p, profileTab, { chosenTab = it }, Modifier.padding(bottom = Spacing.s8))
        }
        // An album's pictures in the order its tab shows them, the lightbox's too.
        val pictures = remember(profile?.pictures, state.pictureOrder) { profile?.pictures.orEmpty().inOrder(state.pictureOrder) }
        val openPicture = { picture: PictureTile, onWall: Boolean -> onOpenPicture(picture.folderId, picture.pictureId, onWall) }
        // A collection opens on the chip lit here.
        val openCollection = { folderId: Long -> onOpenCollection(folderId, state.filter) }
        val uploadSection = @Composable { section: com.regolith.ui.components.UploadSection ->
            UploadSectionView(
                section, { uploadActions?.onSectionAction(it) }, { uploadActions?.retry(it) }, { uploadActions?.remove(it) },
                modifier = Modifier.padding(bottom = Spacing.s8), tagPrefix = "library",
            )
        }
        // Back from the lightbox, the picture it was showing has its tile
        // brought into view before the first frame is drawn, so the picture
        // flies home into it (PictureFocus); a tile already near the screen
        // is left where it is, rather than jumped to the top.
        val focus = LocalPictureFocus.current
        val focusId = focus?.pictureId
        SideEffect {
            val id = focusId ?: return@SideEffect
            focus.pictureId = null
            if (state.viewMode != ViewMode.GRID) return@SideEffect
            if (showPictures) {
                val index = pictures.indexOfFirst { it.pictureId == id }.takeIf { it >= 0 } ?: return@SideEffect
                val item = 2 + (if (uploads.section != null) 1 else 0) + index
                if (!mosaicState.near(item, mosaicAcross)) mosaicState.requestScrollToItem(item)
            } else {
                val index = state.tiles.indexOfFirst { it is LibraryTile.Picture && it.picture.pictureId == id }.takeIf { it >= 0 } ?: return@SideEffect
                val item = leadingItems + index
                if (!gridState.near(item)) gridState.requestScrollToItem(item)
            }
        }
        LaunchedEffect(selectedFileId) {
            val id = selectedFileId ?: return@LaunchedEffect
            if (state.viewMode != ViewMode.GRID || showMoments) return@LaunchedEffect
            val tile = state.tiles.indexOfFirst { it.isSelected(id) }
            if (tile < 0) return@LaunchedEffect
            val index = leadingItems + tile
            withTimeoutOrNull(REVEAL_WINDOW_MS) {
                snapshotFlow { gridState.layoutInfo.viewportSize.width }.distinctUntilChanged().collect {
                    if (!gridState.showsWhole(index)) gridState.animateScrollToItem(index)
                }
            }
        }

        if (showPictures && profile != null && state.viewMode == ViewMode.GRID) {
            // The Images tab: the album's pictures, each at its own shape, down
            // columns a pinch adds or takes away (PicturesAcross).
            LazyVerticalStaggeredGrid(
                columns = StaggeredGridCells.Fixed(mosaicAcross),
                state = mosaicState,
                modifier = Modifier
                    .fillMaxSize()
                    .onGloballyPositioned { wallTopPx = it.positionInParent().y.roundToInt() }
                    .wallPinch(mosaicPinch, mosaicState)
                    .testTag("library_mosaic"),
                contentPadding = PaddingValues(
                    start = Spacing.s18, end = Spacing.s18,
                    bottom = LocalNavPillInsets.current.calculateBottomPadding(),
                ),
                verticalItemSpacing = PictureMosaicGap,
                horizontalArrangement = Arrangement.spacedBy(PictureMosaicGap),
            ) {
                item(key = "library_profile_header", span = StaggeredGridItemSpan.FullLine) { profileHeader(profile) }
                item(key = "library_profile_tabs", span = StaggeredGridItemSpan.FullLine) { profileTabs(profile) }
                uploads.section?.let { section -> item(key = "library_uploads", span = StaggeredGridItemSpan.FullLine) { uploadSection(section) } }
                items(pictures, key = { it.testTag }) { picture ->
                    PictureMosaicTile(
                        artwork = picture.artwork,
                        aspect = picture.aspect,
                        name = picture.title,
                        onClick = { if (selecting) viewModel.togglePicture(picture) else openPicture(picture, false) },
                        onLongClick = { viewModel.beginPictureSelection(picture) },
                        checked = if (selecting) selection.picks(picture) else null,
                        testTag = picture.testTag,
                        poster = picture.poster,
                        gif = picture.gif,
                        videoName = picture.videoName,
                    )
                }
            }
        } else if (showPictures && profile != null) {
            // The same pictures as rows: the thumbnail small, the date and size beside the name.
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().testTag("library_rows"),
                contentPadding = PaddingValues(
                    start = Spacing.s18, end = Spacing.s18,
                    bottom = LocalNavPillInsets.current.calculateBottomPadding(),
                ),
            ) {
                item(key = "library_profile_header") { profileHeader(profile) }
                item(key = "library_profile_tabs") { profileTabs(profile) }
                uploads.section?.let { section -> item(key = "library_uploads") { uploadSection(section) } }
                itemsIndexed(pictures, key = { _, picture -> picture.testTag }) { index, picture ->
                    if (index > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(colors.hairline))
                    ListRow(
                        title = picture.title,
                        meta = pictureMeta(picture),
                        leading = RowLeading.Thumb(picture.artwork, fallbackLabel = picture.name),
                        trailing = if (selecting && selection.picks(picture)) RowTrailing.Checked else RowTrailing.None,
                        onClick = { if (selecting) viewModel.togglePicture(picture) else openPicture(picture, false) },
                        onLongClick = { viewModel.beginPictureSelection(picture) },
                        testTag = picture.testTag,
                    )
                }
            }
        } else if (state.viewMode == ViewMode.GRID) {
            LazyVerticalGrid(
                // Posters per row is the owner's (Settings › Display); see wallColumns.
                // A moment is a 16:9 frame with a name under it, so the Moments
                // tab takes Search's columns for those instead.
                columns = if (showMoments) ThumbCells else WallCells(LocalWindowShape.current.wide, state.postersPerRow.count, wallFullWidth()),
                state = gridState,
                modifier = Modifier
                    .fillMaxSize()
                    .onGloballyPositioned { wallTopPx = it.positionInParent().y.roundToInt() }
                    // Posters only: Moments are 16:9 frames on Search's columns.
                    .wallPinch(pinch.takeIf { pinchable && !showMoments }, gridState)
                    .testTag("library_grid"),
                contentPadding = PaddingValues(
                    start = Spacing.s18, end = Spacing.s18,
                    bottom = LocalNavPillInsets.current.calculateBottomPadding(),
                ),
                horizontalArrangement = Arrangement.spacedBy(Spacing.s8),
                verticalArrangement = Arrangement.spacedBy(Spacing.s8),
            ) {
                if (profile != null) {
                    item(key = "library_profile_header", span = { GridItemSpan(maxLineSpan) }) { profileHeader(profile) }
                    item(key = "library_profile_tabs", span = { GridItemSpan(maxLineSpan) }) { profileTabs(profile) }
                    if (showMoments) {
                        if (moments.isEmpty()) {
                            item(key = "library_moments_empty", span = { GridItemSpan(maxLineSpan) }) { NoMoments(rows = false) }
                        } else {
                            items(moments, key = { it.testTag }) { moment -> MomentTile(moment, onPlayAt) }
                        }
                        return@LazyVerticalGrid
                    }
                }
                // Only the Network tab is affected by a share going away (design:
                // "the message lives there rather than over files that play fine").
                if (showUnreachable) item(span = { GridItemSpan(maxLineSpan) }) { unreachableBlock() }
                // At the top: the videos on their way are what the user just did.
                uploads.section?.let { section -> item(key = "library_uploads", span = { GridItemSpan(maxLineSpan) }) { uploadSection(section) } }
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
                        onOpenCollection = openCollection, onOpenTitle = onOpenTitle,
                        onToggle = viewModel::toggleSelection, onLongPress = viewModel::beginSelection,
                        filter = state.filter,
                        onOpenPicture = { openPicture(it, true) },
                    )
                }
            }
        } else {
            // Rows: no card frame here. A wall can hold a thousand titles, and
            // the design's cards are for short grouped lists (Browse, Settings);
            // a hairline between rows is what keeps a long list readable.
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().testTag("library_rows"),
                contentPadding = PaddingValues(
                    start = Spacing.s18, end = Spacing.s18,
                    bottom = LocalNavPillInsets.current.calculateBottomPadding(),
                ),
            ) {
                if (profile != null) {
                    item(key = "library_profile_header") { profileHeader(profile) }
                    item(key = "library_profile_tabs") { profileTabs(profile) }
                    if (showMoments) {
                        if (moments.isEmpty()) {
                            item(key = "library_moments_empty") { NoMoments(rows = true) }
                        } else {
                            itemsIndexed(moments, key = { _, moment -> moment.testTag }) { index, moment ->
                                if (index > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(colors.hairline))
                                MomentRow(moment, onPlayAt)
                            }
                        }
                        return@LazyColumn
                    }
                }
                if (showUnreachable) item { unreachableBlock() }
                uploads.section?.let { section -> item(key = "library_uploads") { uploadSection(section) } }
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
                        onOpenCollection = openCollection, onOpenTitle = onOpenTitle,
                        onToggle = viewModel::toggleSelection, onLongPress = viewModel::beginSelection,
                        filter = state.filter,
                        onOpenPicture = { openPicture(it, true) },
                    )
                }
            }
        }
    }

    // The pinch's "N across" pill, just inside the top of the wall: under the
    // tabs on a wall, under the floating bar on a profile (whose grid starts
    // at the top of the screen, beneath that bar).
    val pillTopPx = wallTopPx + with(LocalDensity.current) { ((if (profile != null) profileBarHeight() else 0.dp) + Spacing.s8).roundToPx() }
    WallPinchPill(pinch, Modifier.align(Alignment.TopCenter).offset { IntOffset(0, pillTopPx) })
    WallPinchPill(mosaicPinch, Modifier.align(Alignment.TopCenter).offset { IntOffset(0, pillTopPx) })

    // A profile's top bar floats over the page, so the poster's light runs
    // up under it. It fills in with the page's ground, and takes the
    // collection's name, once the header has scrolled up behind it. A
    // selection's bar takes its place, solid from the start, so starting
    // one moves nothing on the page.
    if (profile != null) {
        val barPx = with(LocalDensity.current) { profileBarHeight().roundToPx() }
        val collapseAt = (profileHeaderPx - barPx).coerceAtLeast(1)
        val collapsed by remember(collapseAt, state.viewMode, showPictures) {
            derivedStateOf {
                when {
                    showPictures && state.viewMode == ViewMode.GRID -> mosaicState.firstVisibleItemIndex > 0 || mosaicState.firstVisibleItemScrollOffset >= collapseAt
                    state.viewMode == ViewMode.GRID -> gridState.firstVisibleItemIndex > 0 || gridState.firstVisibleItemScrollOffset >= collapseAt
                    else -> listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset >= collapseAt
                }
            }
        }
        val barGround by animateColorAsState(if (collapsed) colors.ground else colors.ground.copy(alpha = 0f), label = "profile_bar")
        if (selecting) {
            // Select all on the Images tab is its pictures; on the others, the wall's tiles.
            val selectAll = { if (showPictures) viewModel.selectAllPictures(profile.pictures) else viewModel.selectAllHere() }
            SelectionBar(selection!!.itemCount, onBack, selectAll, viewModel::cancelSelection, Modifier.background(colors.ground))
        } else {
            TopBar(
                title = if (collapsed) state.title else "",
                onBack = onBack,
                modifier = Modifier.background(barGround).testTag("library_profile_topbar"),
                actions = listOf(
                    TopBarAction(R.drawable.rg_ic_search_alt, "Search", "library_search_button", onSearch),
                    TopBarAction(R.drawable.rg_ic_sort, "Sort", "library_sort_button") { viewModel.openSortSheet(true) },
                    viewModeAction(state.viewMode, "library_view_mode_button", viewModel::toggleViewMode),
                ),
            )
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


        // The pill becomes this selection's toolbar with Browse's four verbs:
        // a collection is moved, renamed and deleted as its folder, a video as
        // itself and the files that share its name. The dialogs, the move
        // sheet and the messages are the shared host's.
        FileSelectionChrome(
            selection = selection,
            actions = viewModel.fileActions,
            here = viewModel.folderId,
            tagPrefix = "library",
            onDownload = viewModel::downloadSelection,
            onCancel = viewModel::cancelSelection,
            // Nothing but pictures picked: Save and Poster where Download was.
            pictureVerbs = remember(viewModel) {
                PictureVerbs(
                    onSave = viewModel::savePictures,
                    onPoster = { viewModel.posterFromSelection { folderId, pictureId -> onSetPoster(folderId, pictureId, null) } },
                )
            },
        )
        FileActionsHost(viewModel.fileActions, tagPrefix = "library")
        // Pictures, videos and a poster into this collection, the same flows
        // Browse uses for a folder; only the choices differ.
        uploadActions?.let { actions ->
            UploadActionsHost(
                actions = actions,
                title = "Add to ${state.title}",
                detail = uploads.serverName?.let { "On $it" },
                sources = listOf(UploadSource.GALLERY, UploadSource.MEDIA_FILES, UploadSource.POSTER),
                posterNoun = "collection",
                onSendingAway = onSendingAway,
                onPosterPicked = { uri -> viewModel.folderId?.let { onSetPoster(it, null, uri) } },
                note = "Pictures go up as they are, with their names. They land under Images once the upload is done.",
            )
        }
    }
}

/**
 * The wall's top bar while picking: the count, Select all and the X. The
 * back arrow is the screen's own, not a cancel: it walks out of the
 * collection and the picks come with it. Cancelling is the X.
 */
@Composable
private fun SelectionBar(
    count: Int,
    onBack: (() -> Unit)?,
    onSelectAll: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TopBar(
        title = if (count == 1) "1 selected" else "$count selected",
        onBack = onBack,
        modifier = modifier.testTag("library_selection_topbar"),
        actions = listOf(
            TopBarAction(R.drawable.rg_ic_check, "Select all", "library_select_all_button", onSelectAll),
            TopBarAction(R.drawable.rg_ic_close, "Cancel selection", "library_select_cancel_button", onCancel),
        ),
    )
}

/** What the first wall, which is no folder, reads in place of its uploads. */
private val NoUploads: kotlinx.coroutines.flow.StateFlow<UploadActionsState> = kotlinx.coroutines.flow.MutableStateFlow(UploadActionsState())

/** Is this the title the detail pane is showing? Collections are never selected: they open a wall, not a detail. */
private fun LibraryTile.isSelected(selectedFileId: Long?): Boolean =
    selectedFileId != null && this is LibraryTile.Title && fileId == selectedFileId

/**
 * Is item [index] on screen, or close enough to it that bringing it in is
 * not worth moving the wall: read from where the wall starts, since a
 * restored grid has laid nothing out yet when this is asked.
 */
private fun LazyGridState.near(index: Int): Boolean {
    val visible = layoutInfo.visibleItemsInfo
    if (visible.isNotEmpty()) return visible.any { it.index == index }
    return index in firstVisibleItemIndex..(firstVisibleItemIndex + NEAR_ITEMS)
}

/** The same for a mosaic of [across] columns. */
private fun androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState.near(index: Int, across: Int): Boolean {
    val visible = layoutInfo.visibleItemsInfo
    if (visible.isNotEmpty()) return visible.any { it.index == index }
    return index in firstVisibleItemIndex..(firstVisibleItemIndex + across * NEAR_ROWS)
}

/** How many tiles past the first visible one still count as near ([near]): about a screen of posters. */
private const val NEAR_ITEMS = 12

/** The same in rows of a mosaic: a picture is about a third of a screen tall. */
private const val NEAR_ROWS = 3

/** Is grid item [index] on screen from its top edge to its bottom one? */
private fun LazyGridState.showsWhole(index: Int): Boolean {
    val info = layoutInfo
    val item = info.visibleItemsInfo.firstOrNull { it.index == index } ?: return false
    return item.offset.y >= info.viewportStartOffset &&
        item.offset.y + item.size.height <= info.viewportEndOffset - info.afterContentPadding
}

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
    filter: LibraryFilter = LibraryFilter.VIDEOS,
    onOpenPicture: (PictureTile) -> Unit = {},
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
                meta = if (out > 0) "All but $out" else collectionMeta(tile, filter),
                count = collectionCount(tile, filter),
                // A resolution is a video's fact; an album of pictures has none.
                chip = tile.resolutionLabel.ifEmpty { null }.takeIf { filter != LibraryFilter.IMAGES },
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
                // Into the poster on its profile, where it has one.
                flight = tile.artwork.owner,
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
                chip = tile.resolutionLabel.ifEmpty { null },
                // The unwatched dot shares the pick marker's corner, so it
                // stands down while selecting rather than sitting under it.
                unwatched = tile.unwatched && !selecting,
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
                // Into the wide picture at the top of its page.
                flight = tile.artwork.owner,
            )
        }
        is LibraryTile.Picture -> {
            // A picture loose on a wall, cut to the wall's 2:3: it opens the
            // lightbox, and flies into it.
            MediaTile(
                artwork = tile.artwork,
                kind = ArtworkKind.POSTER,
                title = tile.name,
                meta = pictureMeta(tile.picture),
                chip = if (tile.picture.gif) "GIF" else null,
                fallbackLabel = tile.picture.name,
                dimmed = dimmed,
                onClick = if (selecting) {
                    { onToggle(tile) }
                } else {
                    { onOpenPicture(tile.picture) }
                },
                onLongClick = { onLongPress(tile) },
                checked = if (selecting) selection.picks(tile.picture) else null,
                testTag = tile.testTag,
                flight = tile.artwork.owner,
            )
        }
    }
}

/** Is [picture] coming in this selection: picked itself, or inside a picked folder and not taken back out. */
private fun SelectionUiState?.picks(picture: PictureTile): Boolean {
    val s = this ?: return false
    return s.pickedOthers.contains(picture.pictureId) ||
        (s.coversFile(picture.shareId, picture.relPath.substringBeforeLast('/', "")) && !s.excludedOthers.contains(picture.pictureId))
}

/** A collection's line under its name, in the terms of the chip that is lit: its files, its moments, its pictures. */
private fun collectionMeta(tile: LibraryTile.Collection, filter: LibraryFilter): String = when (filter) {
    LibraryFilter.VIDEOS -> formatFileCount(tile.fileCount)
    LibraryFilter.MOMENTS -> formatCount(tile.momentCount, "moment")
    // "3 albums" for a folder of albums, "86 pictures" for an album.
    LibraryFilter.IMAGES -> if (tile.albumCount > 0) formatCount(tile.albumCount, "album") else formatCount(tile.pictureCount, "picture")
}

/** The count on a collection's badge, in the same terms. */
private fun collectionCount(tile: LibraryTile.Collection, filter: LibraryFilter): Int = when (filter) {
    LibraryFilter.VIDEOS -> tile.fileCount
    LibraryFilter.MOMENTS -> tile.momentCount
    LibraryFilter.IMAGES -> tile.pictureCount
}

/**
 * What a collection's wall holds under Moments or Images, for the line under
 * its name: "2 albums · 1 picture", "3 collections · 1 video".
 */
private fun wallCount(tiles: List<LibraryTile>, filter: LibraryFilter): String {
    val collections = tiles.count { it is LibraryTile.Collection }
    val pictures = tiles.count { it is LibraryTile.Picture }
    val videos = tiles.count { it is LibraryTile.Title }
    return listOfNotNull(
        collections.takeIf { it > 0 }?.let { formatCount(it, if (filter == LibraryFilter.IMAGES) "album" else "collection") },
        pictures.takeIf { it > 0 }?.let { formatCount(it, "picture") },
        videos.takeIf { it > 0 }?.let { formatCount(it, "video") },
    ).joinToString(" · ").ifEmpty { "Nothing here" }
}

/** A picture's line: when it was taken (its file's date when it does not say), then its size. */
internal fun pictureMeta(picture: PictureTile): String =
    listOf(formatDate(picture.takenAtMs ?: picture.modifiedAtMs), formatBytes(picture.sizeBytes)).joinToString(" · ")

/** The tab a profile opens on when it is opened with [this] chip lit. */
private fun LibraryFilter.profileTab(): ProfileTab = when (this) {
    LibraryFilter.VIDEOS -> ProfileTab.VIDEOS
    LibraryFilter.MOMENTS -> ProfileTab.MOMENTS
    LibraryFilter.IMAGES -> ProfileTab.IMAGES
}

/**
 * The chips under the Library's tabs (design: Lib-Filter): Videos, Moments,
 * Images, the lit one white. The same [FilterChip] Search uses, in its
 * quieter white, since these choose what a wall is of rather than narrow it.
 */
@Composable
private fun FilterChips(filter: LibraryFilter, onFilter: (LibraryFilter) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).testTag("library_filter_chips"), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (f in LibraryFilter.entries) {
            FilterChip(
                text = f.label,
                selected = f == filter,
                onClick = { onFilter(f) },
                testTag = "library_filter_${f.name.lowercase()}",
                icon = when (f) {
                    LibraryFilter.VIDEOS -> LucideR.drawable.lucide_ic_square_play
                    LibraryFilter.MOMENTS -> LucideR.drawable.lucide_ic_bookmark
                    LibraryFilter.IMAGES -> LucideR.drawable.lucide_ic_image
                },
                ink = true,
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
    filter: LibraryFilter = LibraryFilter.VIDEOS,
    onOpenPicture: (PictureTile) -> Unit = {},
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
                meta = when {
                    out > 0 -> "All but $out"
                    filter != LibraryFilter.VIDEOS -> collectionMeta(tile, filter)
                    else -> listOfNotNull(formatFileCount(tile.fileCount), tile.resolutionLabel.ifEmpty { null }).joinToString(" · ")
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
                title = tile.name,
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
        is LibraryTile.Picture -> ListRow(
            title = tile.name,
            meta = pictureMeta(tile.picture),
            leading = RowLeading.Poster(tile.artwork, fallbackLabel = tile.picture.name),
            trailing = if (selecting && selection.picks(tile.picture)) RowTrailing.Checked else RowTrailing.None,
            minHeight = 64.scaledDp(),
            onClick = if (selecting) {
                { onToggle(tile) }
            } else {
                { onOpenPicture(tile.picture) }
            },
            onLongClick = { onLongPress(tile) },
            testTag = tile.testTag,
            modifier = Modifier.alpha(alpha),
        )
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
    // The app's gutter rather than the platform's dialog width, as everywhere else.
    Dialog(onDismissRequest = onKeep, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxWidth().padding(horizontal = Spacing.s18), contentAlignment = Alignment.Center) {
        Column(
            Modifier
                .widthIn(max = DialogMaxWidth)
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
}

/**
 * "Nothing here yet" / "Nothing scanned yet", over the outline of the wall
 * it stands in for: posters, or poster rows when the wall is in [rows] mode.
 */
@Composable
private fun EmptyWall(scannedOnce: Boolean, rows: Boolean, onScan: () -> Unit, filter: LibraryFilter = LibraryFilter.VIDEOS) {
    val (title, body) = when {
        !scannedOnce -> "Nothing scanned yet" to "Regolith reads the share once to know what is on it. Nothing is copied off it."
        filter == LibraryFilter.MOMENTS -> "No moments here yet" to "Name a chapter while a video plays, and its collection shows up here, opening on its moments."
        filter == LibraryFilter.IMAGES -> "No pictures here yet" to "Folders of photos on the share show up here as albums, and pictures beside your videos join them."
        else -> "Nothing here yet" to "The scan found nothing Regolith can play here."
    }
    EmptyState(
        title = title,
        body = body,
        ghost = if (rows) Ghost.PosterRows(count = 4) else Ghost.Posters(rows = 2),
        action = if (scannedOnce) null else EmptyAction("Scan now", onScan, "library_scan_button"),
        modifier = Modifier.padding(top = Spacing.s12),
        testTag = "library_empty_card",
    )
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
 * "SORT BY" in Michroma 14, a 48dp row at 500 15/20 for each of [choices]:
 * the wall's five ([LibrarySort]), or the Moments tab's three ([MomentSort]).
 *
 * Each row says which way it runs on the right: the one in force shows
 * its current direction, the others the direction they would start in.
 * Tapping the one in force reverses it, like a table header.
 */
@Composable
private fun <C> SortSheet(
    choices: List<C>,
    inForce: C,
    direction: SortDirection,
    onSelect: (C) -> Unit,
    onDismiss: () -> Unit,
) where C : Enum<C>, C : SortChoice {
    RegolithSheet(title = "Sort by", onDismiss = onDismiss, testTag = "library_sort_sheet") {
        choices.forEach { sort ->
            val selected = sort == inForce
            SheetOption(
                label = sort.label,
                selected = selected,
                onClick = { onSelect(sort) },
                testTag = "library_sort_${sort.name.lowercase()}",
                trailing = sort.directionLabel(if (selected) direction else sort.natural),
            )
        }
        Text(
            "Tap the one in use again to reverse it.",
            style = TextStyles.meta, color = RegolithTheme.colors.metadata,
            modifier = Modifier.padding(top = Spacing.s8).testTag("library_sort_hint"),
        )
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

/** Network with no server yet: where the wall will be, and the one way to fill it. */
@Composable
private fun NoSource(onAddServer: () -> Unit) {
    EmptyState(
        title = "Your posters will line up here",
        body = "Add the SMB share your videos live on, and Regolith lists everything on it: films, recordings, anything it can play.",
        ghost = Ghost.Posters(rows = 2),
        action = EmptyAction("Add source server", onAddServer, "library_add_server_button"),
        modifier = Modifier.padding(horizontal = Spacing.s18).padding(top = Spacing.s12),
        testTag = "library_empty_card",
    )
}

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
    onFilter: (DeviceFilter) -> Unit = {},
    askedPhone: Boolean = false,
    onAskPhone: () -> Unit = {},
    onOpenAppSettings: () -> Unit = {},
    /** Settings › Display › Posters per row, so this grid matches the Network wall. */
    postersPerRow: Int = PostersPerRow.DEFAULT.count,
) {
    val colors = RegolithTheme.colors
    val picked = state.picked
    val selecting = picked != null
    // One row renderer for every section, so picking behaves the same
    // whether a copy is ready, arriving or failed. A phone video is drawn by
    // the same row but is never pickable: "Remove download" on the only copy
    // of someone's video would be a delete wearing the wrong name.
    val deviceRow: @Composable (DeviceRow, androidx.compose.ui.unit.Dp, String?, () -> Unit, Boolean) -> Unit =
        { row, minHeight, action, onAction, dimThumb ->
            DeviceRowView(
                row = row,
                minHeight = minHeight,
                onClick = { if (selecting && !row.phone) onToggle(row.fileId) else onOpenTitle(row.fileId) },
                onLongClick = if (row.phone) null else { { onLongPress(row.fileId) } },
                checked = if (selecting && !row.phone) row.fileId in picked!! else null,
                action = action,
                onAction = onAction,
                dimThumb = dimThumb,
            )
        }
    // Tiles, as many across as the Network wall has (wallColumns). Chunked
    // rows rather than a LazyVerticalGrid: this is already inside a
    // LazyColumn, which cannot give a nested lazy grid a height to work with.
    val wide = LocalWindowShape.current.wide
    val fullWidth = wallFullWidth()
    val deviceGrid: @Composable (List<DeviceRow>) -> Unit = { rows ->
        BoxWithConstraints {
        val columns = wallColumns(maxWidth, wide, postersPerRow, fullWidth)
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.s12)) {
            rows.chunked(columns).forEach { rowOfTiles ->
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s8)) {
                    rowOfTiles.forEach { row ->
                        val pickable = selecting && !row.phone
                        MediaTile(
                            artwork = ArtworkRequest(ArtworkOwner.File(row.fileId), ArtworkKind.POSTER),
                            title = row.name,
                            meta = row.meta,
                            onClick = { if (pickable) onToggle(row.fileId) else onOpenTitle(row.fileId) },
                            onLongClick = if (row.phone) null else { { onLongPress(row.fileId) } },
                            checked = if (pickable) row.fileId in picked!! else null,
                            onCheckClick = { onToggle(row.fileId) },
                            selected = pickable && row.fileId in picked!!,
                            testTag = row.testTag,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    repeat(columns - rowOfTiles.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
        }
    }
    val filter = state.filter
    val openFolder = (filter as? DeviceFilter.Folder)?.let { f -> state.phoneFolders.firstOrNull { it.folderId == f.folderId } }
    // A new order is a new list to read from the top, as on the wall.
    val listState = rememberLazyListState()
    var lastOrder by remember { mutableStateOf(state.order) }
    LaunchedEffect(state.order) {
        if (state.order == lastOrder) return@LaunchedEffect
        lastOrder = state.order
        listState.animateScrollToItem(0)
    }
    LazyColumn(
        Modifier.fillMaxSize().testTag("library_device_list"),
        state = listState,
        contentPadding = PaddingValues(
            start = Spacing.s18, end = Spacing.s18,
            bottom = 126.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(Spacing.s18),
    ) {
        // Where things came from, as a row of chips: all of it, only what
        // came off a share, or one phone folder. Only once the phone has
        // folders to offer — with downloads alone there is nothing to narrow.
        if (state.phoneFolders.isNotEmpty() && !selecting) {
            item {
                DeviceChips(state, onFilter)
            }
        }
        if (state.phoneAccess == PhoneAccess.PARTIAL && filter == DeviceFilter.All) {
            item { PartialAccessCard(count = state.phoneCount, onChange = onAskPhone) }
        }

        // One phone folder: a flat list, newest first. Nothing to walk into.
        if (openFolder != null) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.s8)) {
                    Eyebrow("${openFolder.path} · ${formatFileCount(openFolder.videos.size)}", muted = true)
                    if (state.viewMode == ViewMode.GRID) {
                        deviceGrid(openFolder.videos)
                    } else {
                        SurfaceCard(modifier = Modifier.fillMaxWidth().testTag(openFolder.testTag), contentPadding = PaddingValues(horizontal = Spacing.s12)) {
                            openFolder.videos.forEach { row -> deviceRow(row, 64.dp, null, {}, false) }
                        }
                    }
                }
            }
            return@LazyColumn
        }

        val showPhone = filter == DeviceFilter.All
        if (state.noDownloads && (filter == DeviceFilter.Downloads || (state.phoneCount == 0 && state.phoneAccess != PhoneAccess.NONE))) {
            item {
                // A normal resting state rather than a fault, so no button:
                // nothing is wrong, and keeping a copy starts on a title's page.
                EmptyState(
                    title = "Nothing kept on this phone yet",
                    body = "Open a title and choose “Keep on this device”. It lives here and plays with the share out of reach.",
                    ghost = if (state.viewMode == ViewMode.GRID) Ghost.Posters(rows = 2) else Ghost.Rows(count = 4),
                    modifier = Modifier.padding(top = Spacing.s12),
                    testTag = "library_device_empty",
                )
            }
            if (!showPhone) return@LazyColumn
        }
        if (state.ready.isNotEmpty()) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.s8)) {
                    // Named for where the copies came from, because the page
                    // also lists videos that came from nowhere but the phone —
                    // and only these can be removed safely. "Clear all" sits
                    // beside its own eyebrow, the way "Clear failed" sits beside
                    // its own: the page's one page-level act, and it asks before
                    // it does anything.
                    if (selecting) {
                        Eyebrow("Downloaded from shares", muted = true)
                    } else {
                        EyebrowAction("Downloaded from shares", "Clear all", onClearAll, "device_clear_all_button")
                    }
                    if (state.viewMode == ViewMode.GRID) {
                        deviceGrid(state.ready)
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
                    // "Clear failed", not "Clear all": the page now has a Clear
                    // all of its own, and two of them meaning different amounts
                    // would be a trap.
                    EyebrowAction("Failed · ${state.failed.size}", "Clear failed", onClearFailed, "device_clear_failed_button")
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

        // Phone storage, below the downloads and always after them: a copy
        // and a video only the phone has must never be confused, and the
        // order of the page is the first thing that says which is which.
        if (!showPhone) return@LazyColumn
        if (state.phoneAccess == PhoneAccess.NONE) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.s8)) {
                    Eyebrow("Phone storage", muted = true)
                    PhoneAccessPrompt(asked = askedPhone, onAsk = onAskPhone, onOpenSettings = onOpenAppSettings)
                }
            }
            return@LazyColumn
        }
        if (state.phoneFolders.isEmpty()) return@LazyColumn
        item { Eyebrow("Phone storage · ${state.phoneFolders.size} ${if (state.phoneFolders.size == 1) "folder" else "folders"}", muted = true) }
        rowItems(state.phoneFolders, key = { it.testTag }) { folder ->
            PhoneFolderStrip(folder, onOpen = { onFilter(DeviceFilter.Folder(folder.folderId)) }, onOpenTitle = onOpenTitle)
        }
    }
}

/**
 * The origin chips across the top of the device tab: All, Downloads, then
 * one per phone folder in the order the page lists them. The same
 * [FilterChip] Search uses, so a filter looks like a filter everywhere.
 */
@Composable
private fun DeviceChips(state: DeviceUiState, onFilter: (DeviceFilter) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s8), modifier = Modifier.testTag("device_filter_chips")) {
        item { FilterChip("All", state.filter == DeviceFilter.All, { onFilter(DeviceFilter.All) }, "device_filter_all") }
        if (!state.noDownloads) {
            item { FilterChip("Downloads · ${state.ready.size}", state.filter == DeviceFilter.Downloads, { onFilter(DeviceFilter.Downloads) }, "device_filter_downloads", icon = R.drawable.rg_ic_server) }
        }
        rowItems(state.phoneFolders, key = { it.testTag }) { folder ->
            FilterChip(
                "${folder.name} · ${folder.videos.size}",
                (state.filter as? DeviceFilter.Folder)?.folderId == folder.folderId,
                { onFilter(DeviceFilter.Folder(folder.folderId)) },
                "device_filter_folder_${folder.folderId}",
                icon = R.drawable.rg_ic_folder_small,
            )
        }
    }
}

/**
 * One phone folder on the All view: its name, how many, where it is, then
 * its newest videos in a strip. Tapping the name narrows the page to the
 * folder (the same as its chip); tapping a video opens it.
 */
@Composable
private fun PhoneFolderStrip(folder: PhoneFolder, onOpen: () -> Unit, onOpenTitle: (Long) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.s8), modifier = Modifier.testTag(folder.testTag)) {
        ListRow(
            title = folder.name,
            meta = "${formatFileCount(folder.videos.size)} · ${folder.path}",
            leading = RowLeading.IconBox(R.drawable.rg_ic_browse),
            trailing = RowTrailing.Chevron,
            onClick = onOpen,
            testTag = "${folder.testTag}_header",
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s8)) {
            rowItems(folder.videos.take(PHONE_STRIP_MAX), key = { it.fileId }) { row ->
                MediaTile(
                    artwork = ArtworkRequest(ArtworkOwner.File(row.fileId), ArtworkKind.THUMB),
                    kind = ArtworkKind.THUMB,
                    title = row.name,
                    meta = row.meta,
                    onClick = { onOpenTitle(row.fileId) },
                    testTag = row.testTag,
                    modifier = Modifier.width(132.scaledDp()),
                    shape = RoundedCornerShape(10.dp),
                )
            }
        }
    }
}

/**
 * Phone storage before anyone has said yes: what it is, that nothing moves,
 * and the one red button on the page, over the outline of the folder rows
 * it will become. After a refusal the system will not show its sheet again,
 * so the button becomes the way to Android's settings.
 */
@Composable
private fun PhoneAccessPrompt(asked: Boolean, onAsk: () -> Unit, onOpenSettings: () -> Unit) {
    EmptyState(
        title = "Play the videos already on this phone",
        body = "Camera, Movies, Download and the rest, listed here beside your downloads. Nothing is moved, copied or uploaded.",
        ghost = Ghost.Folders(count = 3),
        compact = true,
        action = if (asked) {
            EmptyAction("Open Android settings", onOpenSettings, "device_phone_settings_button")
        } else {
            EmptyAction("Show phone videos", onAsk, "device_phone_allow_button")
        },
        note = if (asked) "Allow Photos and videos there, then come back." else "Android asks next. “Select videos” works too: you will see only the ones you pick.",
        testTag = "device_phone_access_card",
    )
}

/** "Select videos" was chosen: say so, because new videos will not appear on their own. */
@Composable
private fun PartialAccessCard(count: Int, onChange: () -> Unit) {
    val colors = RegolithTheme.colors
    SurfaceCard(modifier = Modifier.fillMaxWidth().testTag("device_phone_partial_card"), contentPadding = PaddingValues(Spacing.s12)) {
        Text(
            when (count) {
                0 -> "No videos picked yet"
                1 -> "Showing the 1 video you picked"
                else -> "Showing the $count videos you picked"
            },
            style = TextStyles.settingLabel, color = colors.ink,
        )
        Spacer(Modifier.height(Spacing.s4))
        Text("New videos on this phone won't appear until you pick them too.", style = TextStyles.meta, color = colors.body)
        Spacer(Modifier.height(Spacing.s12))
        SecondaryButton(text = "Pick more or allow all", onClick = onChange, compact = true, testTag = "device_phone_pick_more_button")
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

/** How long after a title opens the wall keeps checking that its tile is in view: the page's arrival, and a little. */
private const val REVEAL_WINDOW_MS = 600L

/** Videos in one folder's strip on the All view; the folder's chip or header shows the rest. */
private const val PHONE_STRIP_MAX = 12
