package com.regolith.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.media3.common.util.UnstableApi
import androidx.compose.ui.Modifier
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
import com.regolith.ui.components.NavPill
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
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun RegolithNavGraph(appViewModel: AppViewModel) {
    val start by appViewModel.startDestination.collectAsStateWithLifecycle()
    val dimmedTabs by appViewModel.dimmedTabs.collectAsStateWithLifecycle()
    // null = preferences still loading; the system splash is covering us.
    val startKey = start ?: return

    val backStack = rememberNavBackStack(startKey)
    val hazeState = remember { HazeState() }
    // The design's splash: the moon plate for "two seconds at most" on a cold start, then it fades.
    var splash by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) { delay(SPLASH_MS); splash = false }
    val topKey = backStack.lastOrNull() as? RegolithKey
    val currentTab = MainTab.forKey(topKey)

    fun navigateToTab(tab: MainTab, force: Boolean = false) {
        if (tab == currentTab && !force) return
        backStack.clear()
        if (tab != MainTab.HOME) backStack.add(RegolithKey.Home)
        backStack.add(tab.key)
    }

    // Root container. testTagsAsResourceId: without this, uiautomator (and
    // therefore argent's `describe`) cannot see any Compose testTag at all.
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
                entryDecorators = listOf(
                    rememberSaveableStateHolderNavEntryDecorator(),
                    rememberViewModelStoreNavEntryDecorator(),
                ),
                // Screens register as the blur source so the pill frosts
                // whatever scrolls beneath it.
                modifier = Modifier.fillMaxSize().hazeSource(hazeState),
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
                    entry<RegolithKey.Home> {
                        HomeScreen(
                            viewModel = hiltViewModel(),
                            onAddServer = { backStack.add(RegolithKey.AddServer.Search) },
                            onSearch = { backStack.add(RegolithKey.Search) },
                            onOpenTitle = { backStack.add(RegolithKey.TitleDetail(it)) },
                            onPlay = { fileId, startMs -> backStack.add(RegolithKey.Player(fileId, startMs)) },
                            onOpenDevice = { backStack.clear(); backStack.add(RegolithKey.Home); backStack.add(RegolithKey.Library(onDevice = true)) },
                        )
                    }
                    entry<RegolithKey.Library> { key ->
                        LibraryScreen(
                            viewModel = hiltViewModel<LibraryViewModel, LibraryViewModel.Factory>(
                                creationCallback = { it.create(key.folderId) },
                            ),
                            onBack = if (key.folderId == null) null else ({ backStack.removeLastOrNull() }),
                            onOpenCollection = { backStack.add(RegolithKey.Library(it)) },
                            onOpenTitle = { backStack.add(RegolithKey.TitleDetail(it)) },
                            onSearch = { backStack.add(RegolithKey.Search) },
                            onAddServer = { backStack.add(RegolithKey.AddServer.Search) },
                            startOnDevice = key.onDevice,
                        )
                    }
                    entry<RegolithKey.Search> {
                        SearchScreen(
                            viewModel = hiltViewModel(),
                            onCancel = { backStack.removeLastOrNull() },
                            onOpenTitle = { backStack.add(RegolithKey.TitleDetail(it)) },
                            onOpenFolder = { backStack.add(RegolithKey.Browse(it)) },
                        )
                    }
                    entry<RegolithKey.Browse> { key ->
                        BrowseScreen(
                            viewModel = hiltViewModel<BrowseViewModel, BrowseViewModel.Factory>(
                                creationCallback = { it.create(key.folderId) },
                            ),
                            onBack = if (key.folderId == null) null else ({ backStack.removeLastOrNull() }),
                            onOpenFolder = { backStack.add(RegolithKey.Browse(it)) },
                            onOpenFile = { backStack.add(RegolithKey.TitleDetail(it)) },
                            onAddServer = { backStack.add(RegolithKey.AddServer.Search) },
                        )
                    }
                    entry<RegolithKey.TitleDetail> { key ->
                        TitleDetailScreen(
                            viewModel = hiltViewModel<TitleDetailViewModel, TitleDetailViewModel.Factory>(
                                creationCallback = { it.create(key.fileId) },
                            ),
                            onBack = { backStack.removeLastOrNull() },
                            onPlay = { backStack.add(RegolithKey.Player(it)) },
                        )
                    }
                    entry<RegolithKey.Settings> {
                        SettingsScreen(viewModel = hiltViewModel(), onAddServer = { backStack.add(RegolithKey.AddServer.Search) })
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
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(bottom = Spacing.s8),
                )
            }
        }
    }
}

private const val SPLASH_MS = 1_400L
