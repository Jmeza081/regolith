package com.regolith.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
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
import com.regolith.ui.components.LocalNavPillInsets
import com.regolith.ui.components.NAV_PILL_CLEARANCE
import com.regolith.ui.components.NAV_RAIL_INSET
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
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
    val paneListKey = if (windowShape.wide && topKey is RegolithKey.TitleDetail) {
        (backStack.getOrNull(backStack.lastIndex - 1) as? RegolithKey)?.takeIf { it is RegolithKey.Library }
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
    // How wide the list pane asks to be. The rail lives INSIDE that pane, so
    // the wall keeps its three columns only if the pane pays for both; the
    // 55% cap keeps the detail worth reading on a window barely over the
    // two-pane threshold, where the full ask would crush it.
    val listPaneWidth = minOf(NAV_RAIL_INSET + WALL_WIDTH, windowShape.width * 0.55f)
    // The list-detail scene: on a wide window the strategy pairs the top two
    // keys (a wall and the title open from it) into one two-pane scene, out of
    // the SAME back stack — no second navigation structure (G10).
    // shouldHandleSinglePaneLayout = false so a phone keeps the default scene
    // and our own slide transitions, exactly as before F2.
    // Where the divider can rest. Dragging settles onto one of these rather
    // than landing anywhere: a split you cannot reproduce is a split you have
    // to keep fixing. The first collapses the wall to the rail, which is what
    // makes the detail full screen and raises its toggle.
    // Written where the strategy is built, read by the detail entry below. A
    // plain var is enough: both happen in this composition, in this order.
    var paneExpansion: PaneExpansionState? = null
    val railAnchor = remember { PaneExpansionAnchor.Offset.fromStart(NAV_RAIL_INSET) }
    val anchors = remember(listPaneWidth) {
        listOf(railAnchor, PaneExpansionAnchor.Offset.fromStart(listPaneWidth), PaneExpansionAnchor.Proportion(0.85f))
    }
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
            paneExpansionDragHandle = { state -> PaneHandle(state) },
        )
    }
    // No preferredPaneSize: the expansion state owns the divider, and two
    // sources for one number is how they drift apart.
    val listPaneMeta = ListDetailSceneStrategy.listPane(detailPlaceholder = { NoTitleChosen() })
    // The four tab screens sit beside the rail on a wide window. Pushed
    // screens (Player, Title Detail, Add Server) have no rail and take the
    // whole width, so the inset is applied per entry, not on the NavDisplay.
    val tabContent: @Composable (@Composable () -> Unit) -> Unit = { content ->
        Box(Modifier.fillMaxSize().padding(start = if (windowShape.wide) NAV_RAIL_INSET else 0.dp)) { content() }
    }

    // Opening a title. In the pane layout the wall stays live beside the
    // detail, so picking another title REPLACES the open one; stacking two
    // details would leave the wall marking the wrong tile and turn the pane's
    // close control back into a back arrow. On a phone the wall is covered,
    // so the top key is never a detail and this is the plain push it was.
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
    CompositionLocalProvider(LocalWindowShape provides windowShape, LocalNavPillInsets provides pillInsets) {
        Box(
            Modifier
                .fillMaxSize()
                .background(RegolithTheme.colors.ground)
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
                        entry<RegolithKey.Browse>(metadata = tabScreen) { key ->
                            tabContent {
                                BrowseScreen(
                                    viewModel = hiltViewModel<BrowseViewModel, BrowseViewModel.Factory>(
                                        creationCallback = { it.create(key.folderId) },
                                    ),
                                    onBack = if (key.folderId == null) null else ({ backStack.removeLastOrNull() }),
                                    onOpenFolder = { backStack.add(RegolithKey.Browse(it)) },
                                    onOpenFile = { openTitle(it) },
                                    onAddServer = { backStack.add(RegolithKey.AddServer.Search) },
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
                                // Only when the wall is collapsed behind this pane:
                                // that is the state a handle alone is hard to escape.
                                onShowList = paneExpansion
                                    ?.takeIf { paneListKey != null && it.currentAnchor == railAnchor }
                                    ?.let { state -> { scope.launch { state.animateTo(anchors[1]) } } },
                            )
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
                    NavPill(
                        selected = currentTab,
                        onSelect = { navigateToTab(it) },
                        hazeState = hazeState,
                        dimmed = dimmedTabs,
                        vertical = windowShape.wide,
                        modifier = if (windowShape.wide) {
                            // The rail: s18 in from the start edge, centred on the height.
                            Modifier.align(Alignment.CenterStart).padding(start = Spacing.s18)
                        } else {
                            Modifier
                                .align(Alignment.BottomCenter)
                                .navigationBarsPadding()
                                .padding(bottom = Spacing.s8)
                        },
                    )
                }
            }
        }
    }
}

private const val SPLASH_MS = 1_400L

/**
 * The width the Library and Browse walls want beside a detail pane: three
 * tiles at the design's 8dp gaps inside the s18 gutters. The pane asks for
 * this plus the rail it contains.
 */
private val WALL_WIDTH = 364.dp

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
 */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
private fun PaneScaffoldScope.PaneHandle(state: PaneExpansionState) {
    val colors = RegolithTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val dragged by interaction.collectIsDraggedAsState()
    Box(
        Modifier
            .paneExpansionDraggable(state, 48.dp, interaction, state.defaultDragHandleSemantics())
            .testTag("pane_handle"),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .width(if (dragged) 5.dp else 4.dp)
                .height(if (dragged) 56.dp else 40.dp)
                .clip(PillShape)
                .background(if (dragged) colors.inkSoft else colors.raised),
        )
    }
}
