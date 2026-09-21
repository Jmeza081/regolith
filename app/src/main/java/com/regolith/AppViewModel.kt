package com.regolith

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.regolith.data.prefs.AppPreferences
import com.regolith.data.artwork.ArtworkPrefetcher
import com.regolith.data.repository.SourceRepository
import com.regolith.data.scan.ScanRepository
import com.regolith.data.repository.StorageSweeper
import com.regolith.data.transfer.SelectionStore
import com.regolith.data.transfer.TransferRepository
import com.regolith.data.transfer.TransferRepository.Companion.statusEnum
import com.regolith.domain.transfer.TransferStatus
import com.regolith.domain.library.ArtworkTally
import com.regolith.domain.library.BackgroundWork
import com.regolith.domain.library.ScanTally
import com.regolith.domain.library.backgroundWork
import com.regolith.ui.navigation.MainTab
import com.regolith.ui.navigation.RegolithKey
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import com.regolith.data.security.BiometricGate
import com.regolith.domain.security.AuthResult
import com.regolith.domain.security.AppLock
import com.regolith.domain.security.LockAfter
import com.regolith.player.PlaybackSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * App-level state that outlives any single screen: which key the back stack
 * starts on (`null` means "still reading preferences" and keeps the splash
 * up), and whether the nav rail is pinned away on a wide window.
 *
 * A ViewModel is a store that survives rotation. This one is scoped to the
 * Activity, so it is created once per app session.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@HiltViewModel
class AppViewModel @Inject constructor(
    private val prefs: AppPreferences,
    sources: SourceRepository,
    transfers: TransferRepository,
    private val selection: SelectionStore,
    private val playback: PlaybackSession,
    private val biometrics: BiometricGate,
    private val sweeper: StorageSweeper,
    scans: ScanRepository,
    prefetcher: ArtworkPrefetcher,
) : ViewModel() {

    // --- The app lock (fingerprint, face, or the screen lock).

    /**
     * Whether the lock screen is covering everything. Null until the
     * preference has been read — the system splash covers that moment, the
     * same way it waits for [startDestination], so the library is never
     * drawn for a frame before the lock lands on top of it.
     */
    val locked: StateFlow<Boolean?> get() = _locked
    private val _locked = MutableStateFlow<Boolean?>(null)

    /** Whether the lock is switched on at all — the Activity reads it to blank the recents preview. */
    val appLockEnabled: StateFlow<Boolean> = prefs.appLock.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** When the app last went to the background; null on a cold start, which always asks. */
    private var leftAtMs: Long? = null
    private var lockAfter = LockAfter.DEFAULT

    /** The prompt itself. The Activity is passed in and never kept: the system needs a window to draw over. */
    suspend fun authenticate(activity: android.app.Activity): AuthResult =
        biometrics.authenticate(activity, title = "Unlock Regolith", subtitle = "Your library is locked.")

    /**
     * The app went to the background. Nothing is decided here: the clock is
     * only read on the way back, so a long film in the background does not
     * lock behind you while you watch it.
     */
    fun wentToBackground() {
        if (_locked.value == false) leftAtMs = System.currentTimeMillis()
    }

    /** The app came back. [AppLock.shouldAsk] decides, and locking pauses whatever was playing. */
    fun cameToForeground() {
        if (_locked.value == null) return // still starting; the cold-start decision owns it
        if (AppLock.shouldAsk(appLockEnabled.value, lockAfter, leftAtMs, System.currentTimeMillis())) lockNow()
    }

    /** Past the prompt. The grace clock starts again from here. */
    fun unlocked() {
        _locked.value = false
        leftAtMs = null
    }

    private fun lockNow() {
        if (_locked.value == true) return
        if (standDown()) return
        _locked.value = true
        playback.pause()
    }

    /**
     * Whether the lock has to step aside, and does.
     *
     * If the phone has no screen lock and no biometric left — the user took
     * theirs off — there is nothing to check against and no way back in.
     * Holding the library shut would protect nothing, because the phone
     * itself now opens to anyone, and it would lock its owner out for good.
     * So the lock opens the door and switches itself off, which is what
     * Settings will then say.
     */
    private fun standDown(): Boolean {
        if (!biometrics.availability().nothingToCheck) return false
        _locked.value = false
        viewModelScope.launch { prefs.setAppLock(false) }
        return true
    }

    init {
        // Rows a dead process left RUNNING have no worker behind them and
        // would read as "arriving" forever. Put them back in the queue once,
        // at startup, which is the only place that can know a restart happened.
        viewModelScope.launch { transfers.resumeInterrupted() }
        // Downloads and artwork whose rows have gone. Startup for the same
        // reason: nothing else in the app's life is a safe moment to decide
        // that a file on disk is unclaimed, and this is the one place that
        // knows no worker is mid-write. It walks two directories, so it goes
        // off the main thread.
        viewModelScope.launch { withContext(Dispatchers.IO) { sweeper.sweep() } }
        viewModelScope.launch {
            // The cold-start decision, made once and before anything draws.
            lockAfter = prefs.appLockAfter.first()
            _locked.value = false
            if (prefs.appLock.first()) lockNow()
            // Then follow the setting for the rest of the run: turning the
            // lock off lets you straight back in, and turning it on applies
            // from the next time you leave rather than this instant.
            launch { prefs.appLock.collect { on -> if (!on) unlocked() } }
            launch { prefs.appLockAfter.collect { lockAfter = it } }
        }
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

    /**
     * The scan or the artwork walk, for the tier the nav chrome draws above
     * the pill ([com.regolith.ui.components.BackgroundWorkTier]).
     *
     * App-level because the work is: a scan outlives the tab that started
     * it and changes what every other tab can show. Home, Library, Search
     * and Settings each used to say their own version of this, so the
     * answer to "is it still going?" depended on which screen you were on
     * and vanished the moment you left it.
     *
     * Both sources already exist and neither is new state: scan progress is
     * `scan_runs` rows (guardrail G3) and the artwork walk rides
     * WorkManager's own progress. This only joins them into one sentence.
     */
    val backgroundWork: StateFlow<BackgroundWork?> = combine(
        scans.observeRunning(),
        prefetcher.observe(),
    ) { runs, prefetch ->
        backgroundWork(
            scan = runs.takeIf { it.isNotEmpty() }?.let { ScanTally(shares = it.size, files = it.sumOf { r -> r.filesFound }) },
            artwork = prefetch.takeIf { it.running }?.let { ArtworkTally(done = it.done, total = it.total) },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

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
     * No source server: Library, Browse, Shorts and Settings dim. A server out of
     * reach: Home and Browse dim, since only the device tab can do anything.
     */
    val dimmedTabs: StateFlow<Set<MainTab>> = sources.observeServers()
        .map { servers ->
            when {
                servers.isEmpty() -> setOf(MainTab.LIBRARY, MainTab.BROWSE, MainTab.SHORTS, MainTab.SETTINGS)
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
