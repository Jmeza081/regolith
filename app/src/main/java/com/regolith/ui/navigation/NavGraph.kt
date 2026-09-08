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
import com.regolith.ui.browse.BrowseScreen
import com.regolith.ui.components.NavPill
import com.regolith.ui.home.HomeScreen
import com.regolith.ui.library.LibraryScreen
import com.regolith.ui.onboarding.OnboardingScreen
import com.regolith.ui.settings.SettingsScreen
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
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun RegolithNavGraph(appViewModel: AppViewModel) {
    val start by appViewModel.startDestination.collectAsStateWithLifecycle()
    // null = preferences still loading; the system splash is covering us.
    val startKey = start ?: return

    val backStack = rememberNavBackStack(startKey)
    val hazeState = remember { HazeState() }
    val topKey = backStack.lastOrNull() as? RegolithKey
    val currentTab = MainTab.forKey(topKey)

    fun navigateToTab(tab: MainTab) {
        if (tab == currentTab) return
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
                        OnboardingScreen(onFinish = {
                            appViewModel.completeOnboarding()
                            backStack.clear()
                            backStack.add(RegolithKey.Home)
                        })
                    }
                    entry<RegolithKey.Home> { HomeScreen(viewModel = hiltViewModel()) }
                    entry<RegolithKey.Library> { LibraryScreen(viewModel = hiltViewModel()) }
                    entry<RegolithKey.Browse> { BrowseScreen(viewModel = hiltViewModel()) }
                    entry<RegolithKey.Settings> { SettingsScreen(viewModel = hiltViewModel()) }
                    // Phase 1+: TitleDetail, Player, AddServer.*
                },
            )

            if (currentTab != null) {
                NavPill(
                    selected = currentTab,
                    onSelect = ::navigateToTab,
                    hazeState = hazeState,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(bottom = Spacing.s12),
                )
            }
        }
    }
}
