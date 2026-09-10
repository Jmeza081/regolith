package com.regolith.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.background
import com.regolith.ui.theme.PillShape
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.clip
import androidx.compose.material3.adaptive.layout.defaultDragHandleSemantics
import androidx.compose.material3.adaptive.layout.PaneScaffoldScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import android.util.Log
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.media3.common.util.UnstableApi
import androidx.compose.ui.Modifier
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
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
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.material3.adaptive.layout.rememberPaneExpansionState
import androidx.compose.material3.adaptive.layout.PaneExpansionState
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
import com.regolith.ui.addserver.SharePickerScreen
import com.regolith.ui.addserver.SharePickerViewModel
import com.regolith.ui.browse.BrowseScreen
import com.regolith.ui.browse.BrowseViewModel
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
import com.regolith.ui.onboarding.SplashContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeOut
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
    val listPaneWidth = minOf(paneRailInset + WALL_WIDTH, windowShape.width * 0.55f)
    // The list-detail scene: on a wide window the strategy pairs the top two
    // keys (a wall and the title open from it) into one two-pane scene, out of
    // the SAME back stack — no second navigation structure (G10).
    // shouldHandleSinglePaneLayout = false so a phone keeps the default scene
    // and our own slide transitions, exactly as before F2.
    // Where the divider can rest. Dragging settles onto one of these rather
    // than landing anywhere: a split you cannot reproduce is a split you have
    // to keep fixing.
    //
    // The two ends are deliberately asymmetric, the way every foldable mail
    // app does it. Dragging LEFT stops at [minListAnchor] — the wall never
    // collapses, because a two-pane screen with no list is just the detail
    // with a stripe down the side, and the rail is already how you leave.
    // Dragging RIGHT goes all the way: the detail is dismissed rather than
    // squeezed, so nothing ever reflows into a column too narrow to read.
    // The middle one is the even split both are measured against, and the
    // one the handle's reset button restores.
    val minListAnchor = remember(paneRailInset) { PaneExpansionAnchor.Offset.fromStart(paneRailInset + MIN_WALL_WIDTH) }
    val defaultAnchor = remember(listPaneWidth) { PaneExpansionAnchor.Offset.fromStart(listPaneWidth) }
    val fullListAnchor = remember { PaneExpansionAnchor.Proportion(1f) }
    val anchors = remember(minListAnchor, defaultAnchor, fullListAnchor) {
        // A window barely over the two-pane threshold can put the minimum
        // past the even split; ordered and de-duplicated so the state never
        // sees anchors that cross.
        listOf(minListAnchor, defaultAnchor, fullListAnchor).distinct()
    }
    // Written where the strategy is built, read by the reset-on-close effect
    // below. A plain var is enough: both happen in this composition, in order.
    var paneExpansion: PaneExpansionState? = null
    // Keyed on the pane width: the scaffold remembers how wide it made the
    // panes, and folding the device while a detail is open would otherwise
    // keep the cover screen's much narrower list pane on the inner display
    // until the pane was closed. A new key is a fresh scaffold at the new size.
    val listDetail = key(listPaneWidth) {
        val expansion = rememberPaneExpansionState(anchors = anchors, initialAnchoredIndex = 1)
        paneExpansion = expansion
        rememberListDetailSceneStrategy<NavKey>(
            shouldHandleSinglePaneLayout = false,
            // Two panes from 600dp, matching WindowShape.wide. The Material default
            // waits for 840dp, which the inner display clears by only 12dp.
            directive = calculatePaneScaffoldDirectiveWithTwoPanesOnMediumWidth(currentWindowAdaptiveInfo()),
            paneExpansionState = expansion,
            paneExpansionDragHandle = { state ->
                PaneHandle(state, defaultAnchor, onReset = { scope.launch { state.animateTo(defaultAnchor) } })
            },
        )
    }
    // Dragging the wall away is a gesture you make for one title, not a mode
    // you carry between screens: closing the detail puts the divider back, so
    // the next title always opens on the even split. Guarded on the pane
    // having actually been open, so nothing is animated at startup.
    // A film handed over by another app opens the player on top of whatever
    // was there, and is consumed so a rotation does not reopen it.
    val external by appViewModel.external.collectAsStateWithLifecycle()
    LaunchedEffect(external) {
        val video = external ?: return@LaunchedEffect
        backStack.add(RegolithKey.Player.external(video.uri, video.title))
        appViewModel.openedExternal()
    }

    val paneOpen = paneListKey != null
    val paneWasOpen = remember { mutableStateOf(false) }
    LaunchedEffect(paneOpen) {
        if (paneOpen) {
            paneWasOpen.value = true
        } else if (paneWasOpen.value) {
            paneWasOpen.value = false
            paneExpansion?.animateTo(defaultAnchor)
        }
    }
    // No preferredPaneSize: the expansion state owns the divider, and two
    // sources for one number is how they drift apart.
    val listPaneMeta = ListDetailSceneStrategy.listPane(detailPlaceholder = { NoTitleChosen() })
    // The four tab screens sit beside the rail on a wide window. Pushed
    // screens (Player, Title Detail, Add Server) have no rail and take the
    // whole width, so the inset is applied per entry, not on the NavDisplay.
    val tabContent: @Composable (@Composable () -> Unit) -> Unit = { content ->
        // A tab screen is also the LIST pane of the two-pane scene, so it gets
        // the same drawer treatment the detail pane has: laid out at no less
        // than its natural width and clipped, never reflowed, as the divider
        // squeezes it. Only on a wide window — a phone is never narrower than
        // its own content.
        DrawerPane(minWidth = paneRailInset + WALL_WIDTH, enabled = windowShape.wide) {
            Box(Modifier.fillMaxSize().padding(start = railInset)) { content() }
        }
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

    fun navigateToTab(tab: MainTab, force: Boolean = false) {
        if (tab == currentTab && !force) return
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
                            DrawerPane(minWidth = DETAIL_MIN_WIDTH, enabled = paneListKey != null, fade = true) {
                            TitleDetailScreen(
                                viewModel = hiltViewModel<TitleDetailViewModel, TitleDetailViewModel.Factory>(
                                    creationCallback = { it.create(key.fileId) },
                                ),
                                onBack = { backStack.removeLastOrNull() },
                                onPlay = { backStack.add(RegolithKey.Player(it)) },
                                inPane = paneListKey != null,
                            )
                            }
                        }
                        entry<RegolithKey.Settings>(metadata = tabScreen) {
                            tabContent {
                                SettingsScreen(viewModel = hiltViewModel(), onAddServer = { backStack.add(RegolithKey.AddServer.Search) })
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

                if (currentTab != null && !splash) {
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

private const val SPLASH_MS = 1_400L

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
 * How narrow the wall may be dragged before the divider refuses to go
 * further: two poster columns and their gutter. Below this the wall stops
 * being a wall, and a pane that can be dragged out of existence is a pane
 * you lose by accident.
 */
private val MIN_WALL_WIDTH = 240.dp

/**
 * Below this a fading pane is on its way out and stops being composed at
 * all; [DETAIL_MIN_WIDTH] is the width its content is laid out at whatever
 * the pane is actually given.
 */
private val PANE_GONE_WIDTH = 40.dp
private val DETAIL_MIN_WIDTH = 360.dp

/**
 * A pane that slides under the divider rather than shrinking under it.
 *
 * The whole point is that dragging the split resizes the *view*, never the
 * *layout*: content is measured at [minWidth] however narrow the pane gets
 * and the overflow is clipped, so a title that fitted on one line still
 * fits on one line while half of it is off-screen — which is what a drawer
 * does, and what every foldable mail app does with its list.
 *
 * [fade] adds the dismissal on top, for the pane that is allowed to leave:
 * it dims on the way out and stops being composed below [PANE_GONE_WIDTH].
 * The pane that cannot be dismissed does not fade, because it is not going
 * anywhere — it is just partly behind the divider.
 */
@Composable
private fun DrawerPane(
    minWidth: Dp,
    enabled: Boolean = true,
    fade: Boolean = false,
    content: @Composable () -> Unit,
) {
    if (!enabled) {
        content()
        return
    }
    BoxWithConstraints(Modifier.fillMaxSize().clipToBounds()) {
        val width = maxWidth
        if (fade && width <= PANE_GONE_WIDTH) return@BoxWithConstraints
        val visible = if (fade) ((width - PANE_GONE_WIDTH) / (minWidth - PANE_GONE_WIDTH)).coerceIn(0f, 1f) else 1f
        val floor = with(LocalDensity.current) { minWidth.roundToPx() }
        // A Layout rather than a Box: the content has to be MEASURED at the
        // floor and PLACED at the start edge, and a Box that is asked to hold
        // something wider than itself does not promise where it puts it. The
        // overflow then leaves under the divider, which is the whole idea —
        // clipped on the side the divider is on, never on the outer edge.
        Layout(
            content = { Box(Modifier.graphicsLayer { alpha = visible }) { content() } },
            modifier = Modifier.fillMaxSize(),
        ) { measurables, constraints ->
            val w = maxOf(constraints.maxWidth, floor)
            val placeable = measurables.first().measure(
                constraints.copy(minWidth = w, maxWidth = w, minHeight = constraints.maxHeight),
            )
            layout(constraints.maxWidth, constraints.maxHeight) { placeable.place(0, 0) }
        }
    }
}

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

/**
 * The grab handle on the divider between the wall and the detail: a short
 * vertical pill, dim at rest and lit while dragged, the shape every foldable
 * app uses for this. The library's [paneExpansionDraggable] modifier carries
 * the 48dp touch target and the accessibility actions, so the split can also
 * be moved without dragging at all.
 *
 * Whenever the divider is off [defaultAnchor] the handle also carries the
 * control that puts it back (F6). It lives here rather than on either
 * screen because the handle is the one thing on the divider that is always
 * on screen: dragged fully one way the wall is gone, dragged the other the
 * detail is a sliver, and a button drawn inside either pane disappears with
 * it. The bar stays below the button so the handle is still grabbable.
 */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
private fun PaneScaffoldScope.PaneHandle(
    state: PaneExpansionState,
    defaultAnchor: PaneExpansionAnchor,
    onReset: () -> Unit,
) {
    val colors = RegolithTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val dragged by interaction.collectIsDraggedAsState()
    val moved = state.currentAnchor != null && state.currentAnchor != defaultAnchor
    Box(
        Modifier
            .paneExpansionDraggable(state, 48.dp, interaction, state.defaultDragHandleSemantics())
            // The desktop splitter gesture, free now that there is something
            // for it to do. Movement cancels it, so it never eats a drag.
            .pointerInput(moved) { if (moved) detectTapGestures(onDoubleTap = { onReset() }) }
            .testTag("pane_handle"),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Spacing.s8)) {
            if (moved) {
                IconCircleButton(
                    icon = painterResource(R.drawable.rg_ic_split_even),
                    contentDescription = "Even split",
                    onClick = onReset,
                    size = 28.dp,
                    iconSize = 15.dp,
                    testTag = "pane_reset_split",
                )
            }
            Box(
                Modifier
                    .width(if (dragged) 5.dp else 4.dp)
                    .height(if (dragged) 56.dp else 40.dp)
                    .clip(PillShape)
                    .background(if (dragged) colors.inkSoft else colors.raised),
            )
        }
    }
}
