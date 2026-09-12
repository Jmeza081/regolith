package com.regolith

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.regolith.data.prefs.AppPreferences
import com.regolith.data.repository.SourceRepository
import com.regolith.data.transfer.SelectionStore
import com.regolith.data.transfer.TransferRepository
import com.regolith.data.transfer.TransferRepository.Companion.statusEnum
import com.regolith.domain.transfer.TransferStatus
import com.regolith.ui.navigation.MainTab
import com.regolith.ui.navigation.RegolithKey
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    transfers: TransferRepository,
    private val selection: SelectionStore,
) : ViewModel() {

    init {
        // Rows a dead process left RUNNING have no worker behind them and
        // would read as "arriving" forever. Put them back in the queue once,
        // at startup, which is the only place that can know a restart happened.
        viewModelScope.launch { transfers.resumeInterrupted() }
    }

    /**
     * Tabs carrying a notification dot.
     *
     * Lit while anything is queued, copying or paused ("downloading has
     * started") or has failed ("you need to know"). Nothing marks it as
     * seen: there is no new state to persist, and every way of clearing it
     * already exists — the queue draining, a retry, or Clear all. A
     * "since you last looked" dot would need a preference and could get
     * stuck on; this one cannot.
     */
    val tabDots: StateFlow<Set<MainTab>> = transfers.observeAll()
        .map { rows ->
            val notable = rows.any { it.statusEnum() != TransferStatus.DONE }
            if (notable) setOf(MainTab.SETTINGS) else emptySet()
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    /** Leaving the tab that was selecting ends the selection. */
    fun clearSelection() = selection.clear()

    /**
     * The download notification was tapped, and the nav graph has yet to act
     * on it.
     *
     * Held here for the same reason [external] is: the Activity is recreated
     * on every rotation with the same intent still attached, so acting on it
     * where it arrives would jump to the downloads every time the device
     * turned. Consumed exactly once by [openedDownloads].
     */
    private val _openDownloads = MutableStateFlow(false)
    val openDownloads: StateFlow<Boolean> = _openDownloads.asStateFlow()

    fun requestDownloads() {
        _openDownloads.value = true
    }

    fun openedDownloads() {
        _openDownloads.value = false
    }

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

    /**
     * A film another app asked us to play, waiting to be opened.
     *
     * It lives here rather than being read off the Activity's intent where
     * it is needed, because the Activity is recreated on every rotation and
     * its intent is not consumed by being handled — reading it there would
     * reopen the player every time the device turned. Consumed exactly once
     * by [openedExternal].
     */
    private val _external = MutableStateFlow<ExternalVideo?>(null)
    val external: StateFlow<ExternalVideo?> = _external.asStateFlow()

    fun openExternal(uri: String, title: String) {
        _external.value = ExternalVideo(uri, title)
    }

    fun openedExternal() {
        _external.value = null
    }

    fun setRailHidden(hidden: Boolean) {
        viewModelScope.launch { prefs.setRailHidden(hidden) }
    }
}

/** A film handed to Regolith by another app: where it is, and what to call it. */
data class ExternalVideo(val uri: String, val title: String)
