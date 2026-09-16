package com.regolith.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.background
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import android.util.Log
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.media3.common.util.UnstableApi
import androidx.compose.ui.Modifier
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import com.regolith.AppViewModel
import com.regolith.BuildConfig
import com.regolith.R
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.material3.adaptive.layout.rememberPaneExpansionState
import androidx.compose.material3.adaptive.layout.PaneExpansionAnchor
import androidx.compose.material3.adaptive.layout.calculatePaneScaffoldDirectiveWithTwoPanesOnMediumWidth
import androidx.compose.material3.adaptive.navigation3.ListDetailSceneStrategy
import androidx.compose.material3.adaptive.navigation3.rememberListDetailSceneStrategy
import com.regolith.ui.adaptive.LocalWindowShape
import com.regolith.ui.adaptive.rememberWindowShape
import com.regolith.ui.addserver.AddServerViewModel
import com.regolith.ui.addserver.ManualEntryScreen
import com.regolith.ui.addserver.ScanningScreen
import com.regolith.ui.addserver.ScanningViewModel
import com.regolith.ui.addserver.SearchServersScreen
import com.regolith.ui.addserver.FolderPickerScreen
import com.regolith.ui.addserver.FolderPickerViewModel
import com.regolith.ui.addserver.SharePickerScreen
import com.regolith.ui.addserver.SharePickerViewModel
import com.regolith.ui.browse.BrowseScreen
import com.regolith.ui.browse.BrowseViewModel
import com.regolith.ui.onboarding.SPLASH_MS
import com.regolith.ui.player.PlayerScreen
import com.regolith.ui.player.PlayerViewModel
import com.regolith.ui.components.Eyebrow
import com.regolith.ui.components.IconCircleButton
import com.regolith.ui.components.LocalNavPillInsets
import com.regolith.ui.components.NAV_PILL_CLEARANCE
import com.regolith.ui.components.NAV_RAIL_INSET
import com.regolith.ui.components.NAV_RAIL_SPINE_INSET
import com.regolith.ui.components.NavRailSpine
import com.regolith.ui.components.NavPill
import com.regolith.ui.home.ContinueWatchingScreen
import com.regolith.ui.home.HomeScreen
import com.regolith.ui.library.LibraryScreen
import com.regolith.ui.library.LibraryViewModel
import com.regolith.ui.search.SearchScreen
import com.regolith.ui.onboarding.OnboardingScreen
import com.regolith.ui.lock.LockScreen
import com.regolith.ui.onboarding.SplashContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeOut
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.onStart
import com.regolith.ui.settings.SettingsScreen
import com.regolith.ui.titledetail.TitleDetailScreen
import com.regolith.ui.titledetail.TitleDetailViewModel
import com.regolith.ui.theme.RegolithTheme
import com.regolith.ui.theme.Spacing
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource

/**
 * The router. One back stack (a list of [RegolithKey]), one [NavDisplay]
 * that renders the top key, and one [NavPill] floated over it.
 *
 * Tab rule: switching tabs resets the stack to `[Home, tab]` so system back
 * from any tab returns to Home and back from Home leaves the app. Pushed
 * screens (Player, TitleDetail, Add Server) sit above the tab that opened
 * them and hide the pill.
 *
 * Web analogy: `<Router>` + `<Routes>` + a persistent bottom nav rendered
 * outside the route outlet.
 */
// Media3's UnstableApi is a Java opt-in marker; androidx's @OptIn (not Kotlin's) is what lint checks for.
@androidx.annotation.OptIn(UnstableApi::class)
// The adaptive list-detail scene (F2) is still an experimental Material 3 API.
@OptIn(ExperimentalComposeUiApi::class, ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun RegolithNavGraph(appViewModel: AppViewModel) {
    val start by appViewModel.startDestination.collectAsStateWithLifecycle()
    val dimmedTabs by appViewModel.dimmedTabs.collectAsStateWithLifecycle()
    val tabDots by appViewModel.tabDots.collectAsStateWithLifecycle()
    val locked by appViewModel.locked.collectAsStateWithLifecycle()
    // null = preferences still loading; the system splash is covering us.
    val startKey = start ?: return

    val backStack = rememberNavBackStack(startKey)
    val scope = rememberCoroutineScope()
    val hazeState = remember { HazeState() }
    // The design's splash: the moon plate for "two seconds at most" on a cold start, then it fades.
    var splash by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) { delay(SPLASH_MS); splash = false }
    val topKey = backStack.lastOrNull() as? RegolithKey

    // The window as a media query: wide (a foldable's inner display, a tablet)
    // plus fold posture. Provided once here; screens read LocalWindowShape.
    // Recomputed on fold/unfold, which the manifest turns into a recompose
    // rather than an Activity restart. The log line is how the QA loop reads
    // it on the emulator (`adb logcat -s Regolith`).
    val windowShape = rememberWindowShape()
    // On a wide window Title Detail is a PANE beside the wall that opened it,
    // not a pushed screen (F2). The key underneath is then still the visible
    // tab, so the rail keeps its selection and the wall marks the open title.
    // Both walls qualify: a file opened from Browse behaves exactly like one
    // opened from Library, which is what the F2 plan called for.
    val paneListKey = if (windowShape.wide && topKey is RegolithKey.TitleDetail) {
        (backStack.getOrNull(backStack.lastIndex - 1) as? RegolithKey)
            ?.takeIf { it is RegolithKey.Library || it is RegolithKey.Browse }
    } else {
        null
    }
    val currentTab = MainTab.forKey(topKey) ?: MainTab.forKey(paneListKey)
    val paneFileId = (topKey as? RegolithKey.TitleDetail)?.fileId?.takeIf { paneListKey != null }
    LaunchedEffect(windowShape) { if (BuildConfig.DEBUG) Log.i("Regolith", "window shape: $windowShape") }
    // What a tab screen must keep clear for the pill. On a phone the pill
    // floats over the bottom of the list; on a wide window it is a rail on
    // the start edge, reserved by [TabContent] below, so the list only needs
    // to clear the system navigation bar.
    val navBarBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val pillInsets = if (windowShape.wide) PaddingValues(bottom = navBarBottom + Spacing.s18) else PaddingValues(bottom = NAV_PILL_CLEARANCE)

    // The rail can be away in two deliberately different ways (F6):
    //  - PINNED AWAY ([railHidden], remembered in preferences): the layout
    //    hands the rail's 102dp back to the screen, so the wall gets its
    //    third tile at full width. Content reflows, so it only ever happens
    //    because you asked for it.
    //  - IDLE ([railIdle], this session only): the rail slides off the start
    //    edge three seconds after the last touch. The reserved inset does not
    //    change, so nothing reflows and the wall never jumps as you read it.
    // Either way the spine stays on the edge, and tapping it brings the rail
    // back. Web analogy: one is a collapsed sidebar, the other is a toolbar
    // that fades while you scroll.
    val railHidden by appViewModel.railHidden.collectAsStateWithLifecycle()
    val autoHideRail by appViewModel.autoHideRail.collectAsStateWithLifecycle()
    var railIdle by remember { mutableStateOf(false) }
    val railVisible = windowShape.wide && !railHidden && !railIdle
    // F6 reserved the rail's 102dp even while it was slid away, so nothing
    // would reflow. What that actually produced was a screen with an obvious
    // empty stripe down the side and no rail in it — and the two ways of
    // hiding the rail reserving different amounts of space, which read as a
    // bug rather than as a decision. The inset now follows what is on screen
    // either way, and ANIMATES there, which is what "nothing should jump"
    // really wanted.
    val railInsetTarget = when {
        !windowShape.wide -> 0.dp
        railVisible -> NAV_RAIL_INSET
        else -> NAV_RAIL_SPINE_INSET
    }
    val railInset by animateDpAsState(railInsetTarget, label = "railInset")
    // Pane geometry keys off the PINNED state only. It must not follow the
    // animation: the scaffold below is keyed on the pane width, so an
    // animated one would rebuild it on every frame of a retraction.
    val paneRailInset = when {
        !windowShape.wide -> 0.dp
        railHidden -> NAV_RAIL_SPINE_INSET
        else -> NAV_RAIL_INSET
    }
    // Every touch in the app, observed without consuming (Initial pass) and
    // reported down a flow rather than into state, so a scroll does not
    // recompose the tree on every frame. collectLatest restarts the delay on
    // each touch: the rail retracts only once you have actually stopped.
    val touches = remember { MutableSharedFlow<Unit>(extraBufferCapacity = 1) }
    LaunchedEffect(autoHideRail, windowShape.wide, railHidden) {
        railIdle = false
        if (!autoHideRail || !windowShape.wide || railHidden) return@LaunchedEffect
        touches.onStart { emit(Unit) }.collectLatest {
            delay(RAIL_IDLE_MS)
            railIdle = true
        }
    }

    // How wide the list pane asks to be. The rail lives INSIDE that pane, so
    // the wall keeps its three columns only if the pane pays for both; the
    // 55% cap keeps the detail worth reading on a window barely over the
    // two-pane threshold, where the full ask would crush it.
    //
    // THE SPLIT DOES NOT MOVE (F13). A draggable divider was built over three
    // rounds and made the app worse every time: whatever the two panes did as
    // it moved — reflow, clip, fade — something on one side of the screen was
    // always wrong, and fixing one side broke the other. An even split is
    // right for both and needs no handle, no anchors, no reset control and no
    // rule about what happens at the extremes. What the divider was really
    // for — seeing the whole wall — is what closing the detail already does.
    val listDetail = rememberListDetailSceneStrategy<NavKey>(
        shouldHandleSinglePaneLayout = false,
        // Two panes from 600dp, matching WindowShape.wide. The Material default
        // waits for 840dp, which the inner display clears by only 12dp.
        directive = calculatePaneScaffoldDirectiveWithTwoPanesOnMediumWidth(currentWindowAdaptiveInfo()),
        paneExpansionState = rememberPaneExpansionState(anchors = EVEN_SPLIT, initialAnchoredIndex = 0),
        // No drag handle: the divider is a line, not a control.
        paneExpansionDragHandle = null,
    )

    // A film handed over by another app opens the player on top of whatever
    // was there, and is consumed so a rotation does not reopen it.
    val external by appViewModel.external.collectAsStateWithLifecycle()
    LaunchedEffect(external) {
        val video = external ?: return@LaunchedEffect
        backStack.add(RegolithKey.Player.external(video.uri, video.title))
        appViewModel.openedExternal()
    }

    // A selection survives walking the tree — that is the whole point of the
    // shared store, and a pick three folders deep needs it. What ends it is
    // arriving somewhere that cannot act on it: only Browse, Library and
    // Search draw the bar, so anywhere else the picks would be invisible and
    // unreachable. One rule covers back, the nav pill and a push into the
    // player, where three separate ones drifted apart.
    val canSelectHere = currentTab == MainTab.BROWSE ||
        currentTab == MainTab.LIBRARY ||
        topKey is RegolithKey.Search
    LaunchedEffect(canSelectHere) {
        if (!canSelectHere) appViewModel.clearSelection()
    }

    // The download notification was tapped. Downloads have no destination of
    // their own by design, so this is Library's own device tab.
    val openDownloads by appViewModel.openDownloads.collectAsStateWithLifecycle()
    LaunchedEffect(openDownloads) {
        if (!openDownloads) return@LaunchedEffect
        backStack.clear()
        backStack.add(RegolithKey.Home)
        backStack.add(RegolithKey.Library(onDevice = true))
        appViewModel.openedDownloads()
    }

    val listPaneMeta = ListDetailSceneStrategy.listPane(detailPlaceholder = { NoTitleChosen() })
    // The four tab screens sit beside the rail on a wide window. Pushed
    // screens (Player, Title Detail, Add Server) have no rail and take the
    // whole width, so the inset is applied per entry, not on the NavDisplay.
    val tabContent: @Composable (@Composable () -> Unit) -> Unit = { content ->
        // The rail is allowed to resize what is beside it: that is the point
        // of retracting it. The column lays out to whatever is left and lays
        // out again when the rail goes away — no clipping, because nothing is
        // being dragged any more and the width only changes when the rail does.
        Box(Modifier.fillMaxSize().padding(start = railInset)) { content() }
    }

    // Opening a title. In the pane layout the wall stays live beside the
    // detail, so picking another title REPLACES the open one; stacking two
    // details would leave the wall marking the wrong tile and turn the pane's
    // close control back into a back arrow. On a phone the wall is covered,
    // so the top key is never a detail and this is the plain push it was.
    // Play all / Shuffle from a collection. The order is the one the wall was
    // showing, shuffled here rather than in the session so the session never
    // has to know what a wall is; the queue rides on the Player key so it
    // survives the process being killed.
    fun playAll(fileIds: List<Long>, shuffle: Boolean) {
        val queue = if (shuffle) fileIds.shuffled() else fileIds
        val first = queue.firstOrNull() ?: return
        backStack.add(RegolithKey.Player(first, queue = queue))
    }

    fun openTitle(fileId: Long) {
        if (windowShape.wide && backStack.lastOrNull() is RegolithKey.TitleDetail) backStack.removeLastOrNull()
        backStack.add(RegolithKey.TitleDetail(fileId))
    }

    /**
     * A tab cell switches tabs; the CURRENT tab's cell brings its stack back
     * to the top — Library three folders deep becomes Library. Already at
     * the top, it does nothing, so a stray tap does not rebuild the screen.
     */
    fun navigateToTab(tab: MainTab, force: Boolean = false) {
        if (backStack.lastOrNull() == tab.key && !force) return
        backStack.clear()
        if (tab != MainTab.HOME) backStack.add(RegolithKey.Home)
        backStack.add(tab.key)
    }

    // Root container. testTagsAsResourceId: without this, uiautomator (and
    // therefore argent's `describe`) cannot see any Compose testTag at all.
    CompositionLocalProvider(
        LocalWindowShape provides windowShape,
        LocalNavPillInsets provides pillInsets,
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(RegolithTheme.colors.ground)
                // Watch every touch without taking it: the Initial pass sees
                // the event before any child, and nothing here consumes it.
                // This is what tells the rail you are still using the app.
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            awaitPointerEvent(PointerEventPass.Initial)
                            touches.tryEmit(Unit)
                        }
                    }
                }
                .semantics { testTagsAsResourceId = true },
        ) {
            Box(Modifier.fillMaxSize()) {
                NavDisplay(
                    backStack = backStack,
                    onBack = { backStack.removeLastOrNull() },
                    sceneStrategies = listOf(listDetail),
                entryDecorators = listOf(
                        rememberSaveableStateHolderNavEntryDecorator(),
                        rememberViewModelStoreNavEntryDecorator(),
                    ),
                    // Screens register as the blur source so the pill frosts
                    // whatever scrolls beneath it.
                    modifier = Modifier.fillMaxSize().hazeSource(hazeState),
                    // Slide in / slide back instead of Navigation 3's scale-and-fade; see Transitions.kt.
                    transitionSpec = { pushSpec(this) },
                    popTransitionSpec = { popSpec(this) },
                    predictivePopTransitionSpec = { _ -> popSpec(this) },
                    entryProvider = entryProvider {
                        entry<RegolithKey.Onboarding> {
                            OnboardingScreen(
                                onFinish = {
                                    appViewModel.completeOnboarding()
                                    backStack.clear()
                                    backStack.add(RegolithKey.Home)
                                },
                                onFindServer = {
                                    appViewModel.completeOnboarding()
                                    backStack.clear()
                                    backStack.add(RegolithKey.Home)
                                    backStack.add(RegolithKey.AddServer.Search)
                                },
                            )
                        }
                        entry<RegolithKey.AddServer.Search> {
                            SearchServersScreen(
                                viewModel = hiltViewModel(),
                                onBack = { backStack.removeLastOrNull() },
                                onPick = { host -> backStack.add(RegolithKey.AddServer.Manual(prefill = "smb://${host.host.host}" + (if (host.host.port != 445) ":${host.host.port}" else ""))) },
                                onManual = { backStack.add(RegolithKey.AddServer.Manual()) },
                            )
                        }
                        entry<RegolithKey.Home>(metadata = tabScreen) {
                            tabContent {
                                HomeScreen(
                                    viewModel = hiltViewModel(),
                                    onAddServer = { backStack.add(RegolithKey.AddServer.Search) },
                                    onSearch = { backStack.add(RegolithKey.Search) },
                                    onOpenTitle = { openTitle(it) },
                                    onPlay = { fileId, startMs -> backStack.add(RegolithKey.Player(fileId, startMs)) },
                                    onOpenDevice = { backStack.clear(); backStack.add(RegolithKey.Home); backStack.add(RegolithKey.Library(onDevice = true)) },
                                    onOpenContinueWatching = { backStack.add(RegolithKey.ContinueWatching) },
                                )
                            }
                        }
                        entry<RegolithKey.Library>(metadata = tabScreen + listPaneMeta) { key ->
                            tabContent {
                                LibraryScreen(
                                    viewModel = hiltViewModel<LibraryViewModel, LibraryViewModel.Factory>(
                                        creationCallback = { it.create(key.folderId) },
                                    ),
                                    onBack = if (key.folderId == null) null else ({ backStack.removeLastOrNull() }),
                                    onOpenCollection = { backStack.add(RegolithKey.Library(it)) },
                                    onOpenTitle = { openTitle(it) },
                                    onSearch = { backStack.add(RegolithKey.Search) },
                                    onAddServer = { backStack.add(RegolithKey.AddServer.Search) },
                                    startOnDevice = key.onDevice,
                                    selectedFileId = paneFileId,
                                    // Only inside a collection: on the root, "all" would
                                    // mean every file on every share.
                                    onPlayAll = if (key.folderId != null) ::playAll else null,
                                )
                            }
                        }
                        entry<RegolithKey.ContinueWatching> {
                            ContinueWatchingScreen(
                                viewModel = hiltViewModel(),
                                onBack = { backStack.removeLastOrNull() },
                                onPlay = { fileId, startMs -> backStack.add(RegolithKey.Player(fileId, startMs)) },
                            )
                        }
                        entry<RegolithKey.Search> {
                            SearchScreen(
                                viewModel = hiltViewModel(),
                                onCancel = { backStack.removeLastOrNull() },
                                onOpenTitle = { openTitle(it) },
                                onOpenFolder = { backStack.add(RegolithKey.Browse(it)) },
                                // A point of interest opens the player at that time (P9).
                                onPlayAt = { fileId, ms -> backStack.add(RegolithKey.Player(fileId, startMs = ms)) },
                            )
                        }
                        entry<RegolithKey.Browse>(metadata = tabScreen + listPaneMeta) { key ->
                            tabContent {
                                BrowseScreen(
                                    selectedFileId = paneFileId,
                                    viewModel = hiltViewModel<BrowseViewModel, BrowseViewModel.Factory>(
                                        creationCallback = { it.create(key.folderId) },
                                    ),
                                    onBack = if (key.folderId == null) null else ({ backStack.removeLastOrNull() }),
                                    onOpenFolder = { backStack.add(RegolithKey.Browse(it)) },
                                    onOpenFile = { openTitle(it) },
                                    onAddServer = { backStack.add(RegolithKey.AddServer.Search) },
                                    onPlayAll = ::playAll,
                                    // The tree restarts the Browse chain instead of pushing onto
                                    // it, so back from a jump leaves Browse rather than walking
                                    // through every folder the tree was used to skip.
                                    onOpenTree = if (windowShape.wide) {
                                        { folderId ->
                                            backStack.clear()
                                            backStack.add(RegolithKey.Home)
                                            backStack.add(RegolithKey.Browse(folderId))
                                        }
                                    } else {
                                        null
                                    },
                                )
                            }
                        }
                        entry<RegolithKey.TitleDetail>(metadata = ListDetailSceneStrategy.detailPane()) { key ->
                            TitleDetailScreen(
                                viewModel = hiltViewModel<TitleDetailViewModel, TitleDetailViewModel.Factory>(
                                    creationCallback = { it.create(key.fileId) },
                                ),
                                onBack = { backStack.removeLastOrNull() },
                                onPlay = { backStack.add(RegolithKey.Player(it)) },
                                inPane = paneListKey != null,
                            )
                        }
                        entry<RegolithKey.Settings>(metadata = tabScreen) {
                            tabContent {
                                SettingsScreen(
                                    viewModel = hiltViewModel(),
                                    onAddServer = { backStack.add(RegolithKey.AddServer.Search) },
                                    // Inline rather than navigateToTab, which cannot carry the
                                    // onDevice argument. Downloads have no destination of their
                                    // own by design; Library › On this device is where they live.
                                    onOpenDownloads = {
                                        backStack.clear()
                                        backStack.add(RegolithKey.Home)
                                        backStack.add(RegolithKey.Library(onDevice = true))
                                    },
                                )
                            }
                        }

                        entry<RegolithKey.AddServer.Manual> { key ->
                            ManualEntryScreen(
                                viewModel = hiltViewModel<AddServerViewModel, AddServerViewModel.Factory>(creationCallback = { it.create(key.prefill) }),
                                onBack = { backStack.removeLastOrNull() },
                                onConnected = { serverId -> backStack.add(RegolithKey.AddServer.Shares(serverId)) },
                            )
                        }
                        entry<RegolithKey.AddServer.Shares> { key ->
                            SharePickerScreen(
                                viewModel = hiltViewModel<SharePickerViewModel, SharePickerViewModel.Factory>(
                                    creationCallback = { it.create(key.serverId) },
                                ),
                                onBack = { backStack.removeLastOrNull() },
                                onContinue = { backStack.add(RegolithKey.AddServer.Scanning(key.serverId)) },
                                onChooseFolders = { shareId -> backStack.add(RegolithKey.AddServer.Folders(shareId)) },
                            )
                        }
                        entry<RegolithKey.AddServer.Folders> { key ->
                            FolderPickerScreen(
                                viewModel = hiltViewModel<FolderPickerViewModel, FolderPickerViewModel.Factory>(
                                    creationCallback = { it.create(key.shareId, key.relPath) },
                                ),
                                onBack = { backStack.removeLastOrNull() },
                                onOpen = { relPath -> backStack.add(RegolithKey.AddServer.Folders(key.shareId, relPath)) },
                                // Done climbs straight back out to the share list, however deep you went.
                                onDone = { backStack.removeAll { it is RegolithKey.AddServer.Folders } },
                            )
                        }
                        entry<RegolithKey.AddServer.Scanning> { key ->
                            ScanningScreen(
                                viewModel = hiltViewModel<ScanningViewModel, ScanningViewModel.Factory>(
                                    creationCallback = { it.create(key.serverId) },
                                ),
                                // Either way the Add Server flow is over: drop it from the stack.
                                onBackground = { navigateToTab(MainTab.HOME, force = true) },
                                onDone = { navigateToTab(MainTab.LIBRARY, force = true) },
                            )
                        }
                        entry<RegolithKey.Player> { key ->
                            PlayerScreen(
                                viewModel = hiltViewModel<PlayerViewModel, PlayerViewModel.Factory>(
                                    creationCallback = { it.create(key) },
                                ),
                                onBack = { backStack.removeLastOrNull() },
                            )
                        }
                        // Phase 6: AddServer.Search.
                    },
                )

                AnimatedVisibility(visible = splash, exit = fadeOut(), modifier = Modifier.fillMaxSize()) { SplashContent() }
                // The app lock, over everything including the splash: what it
                // covers is the whole point of it.
                AnimatedVisibility(visible = locked == true, exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
                    LockScreen(authenticate = appViewModel::authenticate, onUnlocked = appViewModel::unlocked)
                }

                if (currentTab != null && !splash && locked != true) {
                    // On a wide window the rail and its spine occupy the same
                    // edge and swap: whichever is not showing has slid out.
                    AnimatedVisibility(
                        visible = !railVisible && windowShape.wide,
                        enter = fadeIn(),
                        exit = fadeOut(),
                        modifier = Modifier.align(Alignment.CenterStart).padding(start = Spacing.s8),
                    ) {
                        NavRailSpine(
                            selected = currentTab,
                            dimmed = dimmedTabs,
                            dots = tabDots,
                            hazeState = hazeState,
                            onExpand = {
                                if (railHidden) appViewModel.setRailHidden(false) else railIdle = false
                                touches.tryEmit(Unit)
                            },
                        )
                    }
                    AnimatedVisibility(
                        visible = railVisible || !windowShape.wide,
                        enter = if (windowShape.wide) slideInHorizontally { -it } + fadeIn() else fadeIn(),
                        exit = if (windowShape.wide) slideOutHorizontally { -it } + fadeOut() else fadeOut(),
                        modifier = if (windowShape.wide) {
                            // The rail: s18 in from the start edge, centred on the height.
                            Modifier.align(Alignment.CenterStart).padding(start = Spacing.s18)
                        } else {
                            Modifier
                                .align(Alignment.BottomCenter)
                                .navigationBarsPadding()
                                .padding(bottom = Spacing.s8)
                        },
                    ) {
                        // On a wide window the rail and the control that pins
                        // it away are two objects, not one: the pill is four
                        // tabs and nothing else, and the circle beneath it is
                        // plainly a control — the same 44dp frosted circle the
                        // detail pane closes with. They slide as one group, so
                        // the edge never shows half a nav.
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Spacing.s12)) {
                            NavPill(
                                selected = currentTab,
                                onSelect = { navigateToTab(it) },
                                hazeState = hazeState,
                                dimmed = dimmedTabs,
                                dots = tabDots,
                                vertical = windowShape.wide,
                            )
                            if (windowShape.wide) {
                                IconCircleButton(
                                    icon = painterResource(R.drawable.rg_ic_chevron_left),
                                    contentDescription = "Hide the rail",
                                    onClick = { appViewModel.setRailHidden(true) },
                                    size = 44.dp,
                                    iconSize = 20.dp,
                                    testTag = "nav_rail_hide_button",
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}


/**
 * How long the rail waits after the last touch before it slides away. The
 * same three seconds the player's chrome uses, for the same reason: long
 * enough that it never goes while you are aiming at it.
 */
private const val RAIL_IDLE_MS = 3_000L

/**
 * The width the Library and Browse walls want beside a detail pane: three
 * tiles at the design's 8dp gaps inside the s18 gutters. The pane asks for
 * this plus the rail it contains.
 */
private val WALL_WIDTH = 364.dp

/**
 * The only place the divider is ever put: down the middle. A list of one
 * anchor is how the scaffold is told the split does not move.
 */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
private val EVEN_SPLIT = listOf(PaneExpansionAnchor.Proportion(0.5f))

/**
 * The detail pane before a title is chosen (wide windows only). The wall is
 * the content; this side says why it is empty and nothing more.
 */
@Composable
private fun NoTitleChosen() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Eyebrow("Choose a title", muted = true)
    }
}
