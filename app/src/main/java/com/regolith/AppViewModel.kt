package com.regolith

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.regolith.data.prefs.AppPreferences
import com.regolith.data.repository.SourceRepository
import com.regolith.ui.navigation.MainTab
import com.regolith.ui.navigation.RegolithKey
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * App-level state that outlives any single screen: which key the back stack
 * starts on (`null` means "still reading preferences" and keeps the splash
 * up), and whether the nav rail is pinned away on a wide window.
 *
 * A ViewModel is a store that survives rotation. This one is scoped to the
 * Activity, so it is created once per app session.
 */
@HiltViewModel
class AppViewModel @Inject constructor(
    private val prefs: AppPreferences,
    sources: SourceRepository,
) : ViewModel() {

    /**
     * Tabs drawn at 22% in the pill (design: "Library and Browse dim in
     * the pill rather than vanishing, so the app never changes shape").
     * No source server: Library, Browse and Settings dim. A server out of
     * reach: Home and Browse dim, since only the device tab can do anything.
     */
    val dimmedTabs: StateFlow<Set<MainTab>> = sources.observeServers()
        .map { servers ->
            when {
                servers.isEmpty() -> setOf(MainTab.LIBRARY, MainTab.BROWSE, MainTab.SETTINGS)
                servers.all { it.unreachableSinceMs != null } -> setOf(MainTab.HOME, MainTab.BROWSE)
                else -> emptySet()
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    val startDestination: StateFlow<RegolithKey?> = prefs.onboardingDone
        .map<Boolean, RegolithKey?> { done -> if (done) RegolithKey.Home else RegolithKey.Onboarding }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun completeOnboarding() {
        viewModelScope.launch { prefs.setOnboardingDone(true) }
    }

    /**
     * The nav rail pinned away on a wide window (F6). App-level rather than
     * per-screen: the rail is drawn once, by the nav graph, so the state that
     * hides it belongs at the same level.
     */
    val railHidden: StateFlow<Boolean> = prefs.railHidden
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** Settings › Display › Auto-hide the rail: whether the idle timer runs at all. */
    val autoHideRail: StateFlow<Boolean> = prefs.autoHideRail
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    /** Settings › Display › Moving tiles: provided to the tree as LocalMovingTiles. */
    val movingTiles: StateFlow<Boolean> = prefs.movingTiles
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun setRailHidden(hidden: Boolean) {
        viewModelScope.launch { prefs.setRailHidden(hidden) }
    }
}
