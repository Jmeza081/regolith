package com.regolith.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil3.ImageLoader
import com.regolith.data.artwork.ArtworkRepository
import com.regolith.data.db.ScanRunEntity
import com.regolith.data.artwork.ArtworkPrefetcher
import com.regolith.data.demo.DemoLibrary
import com.regolith.data.prefs.AppPreferences
import android.app.Activity
import com.regolith.data.security.BiometricGate
import com.regolith.domain.security.AuthResult
import com.regolith.domain.media.ShortsLength
import com.regolith.domain.display.NavHideAfter
import com.regolith.domain.security.LockAfter
import com.regolith.data.repository.DeviceLibrary
import com.regolith.data.repository.PhoneLibrary
import com.regolith.domain.media.PhonePaths
import com.regolith.data.repository.SourceRepository
import com.regolith.domain.media.DeviceSource
import com.regolith.domain.media.LocalSource
import com.regolith.data.repository.UserChapterRepository
import com.regolith.data.scan.ScanRepository
import com.regolith.data.transfer.TransferRepository
import com.regolith.data.transfer.TransferRepository.Companion.statusEnum
import com.regolith.domain.transfer.TransferStatus
import com.regolith.ui.util.formatBytes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * State holder for the Settings tab (design section 11): shares with
 * their state, scan all, the one destructive confirm, playback
 * preferences, the media cache.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val sources: SourceRepository,
    private val scans: ScanRepository,
    private val prefs: AppPreferences,
    private val artwork: ArtworkRepository,
    private val imageLoader: ImageLoader,
    private val demo: DemoLibrary,
    private val prefetcher: ArtworkPrefetcher,
    private val transfers: TransferRepository,
    private val userChapters: UserChapterRepository,
    private val biometrics: BiometricGate,
    private val deviceLibrary: DeviceLibrary,
    private val phone: PhoneLibrary,
) : ViewModel() {
    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val shares = sources.observeEnabledShares()
            val runs = shares.flatMapLatest { list -> if (list.isEmpty()) flowOf(emptyList()) else scans.observeLatest(list.map { it.id }) }
            combine(sources.observeServers(), shares, runs) { servers, shareList, runList ->
                servers.mapNotNull { server ->
                    // "On this device" is not a source server: it has no
                    // address, nothing to scan and nothing to disconnect FROM.
                    // It is already represented by the Downloads card below and
                    // by Library's own tab, and a Disconnect button beside it
                    // would offer to delete the copies this whole source exists
                    // to keep ([DeviceSource]).
                    if (DeviceSource.isDevice(server.host.host)) return@mapNotNull null
                    val own = shareList.filter { it.serverId == server.id }
                    val ownRuns = runList.filter { r -> own.any { it.id == r.shareId } }
                    val running = ownRuns.filter { it.status == ScanRunEntity.RUNNING }
                    val free = own.mapNotNull { it.freeBytes }.maxOrNull()
                    val reachable = server.unreachableSinceMs == null
                    val status = when {
                        running.isNotEmpty() -> "Scanning · ${"%,d".format(running.sumOf { it.filesFound })} files"
                        !reachable -> "out of reach"
                        free != null -> "${formatBytes(free)} free"
                        else -> "${own.size} share" + if (own.size == 1) "" else "s"
                    }
                    // A source on the phone is not on the network, so it has
                    // no address to print under its name.
                    val host = if (LocalSource.isLocal(server.host.host)) {
                        ""
                    } else {
                        server.host.host + if (server.host.port != 445) ":${server.host.port}" else ""
                    }
                    ServerRow(server.id, server.name, status, running.isNotEmpty(), reachable = reachable, showing = reachable && own.isNotEmpty(), host = host)
                }
            }.collect { rows -> _uiState.update { it.copy(servers = rows) } }
        }
        viewModelScope.launch { prefetcher.observe().collect { p -> _uiState.update { it.copy(prefetch = p) } } }
        viewModelScope.launch {
            combine(transfers.observeAll(), transfers.observePendingPicks()) { rows, pendingPicks ->
                val active = rows.filter { it.statusEnum() in TransferRepository.ACTIVE_STATUSES }
                val storage = withContext(Dispatchers.IO) { transfers.storage() }
                DownloadsStatus(
                    arriving = active.size,
                    done = rows.count { it.statusEnum() == TransferStatus.DONE },
                    failed = rows.count { it.statusEnum() == TransferStatus.FAILED },
                    bytesDone = active.sumOf { it.bytesDone },
                    bytesTotal = active.sumOf { it.totalBytes },
                    discovering = pendingPicks > 0,
                    usedBytes = storage.usedBytes,
                    deviceTotalBytes = storage.totalBytes,
                )
            }.collect { d -> _uiState.update { it.copy(downloads = d) } }
        }
        viewModelScope.launch { prefs.hardwareDecoding.collect { v -> _uiState.update { it.copy(hardwareDecoding = v) } } }
        viewModelScope.launch { prefs.scrubThumbnails.collect { v -> _uiState.update { it.copy(scrubThumbnails = v) } } }
        viewModelScope.launch { prefs.autoplayNext.collect { v -> _uiState.update { it.copy(autoplayNext = v) } } }
        viewModelScope.launch { prefs.autoplayImmediately.collect { v -> _uiState.update { it.copy(autoplayImmediately = v) } } }
        viewModelScope.launch { prefs.autoHideRail.collect { v -> _uiState.update { it.copy(autoHideRail = v) } } }
        viewModelScope.launch { prefs.navHideAfter.collect { v -> _uiState.update { it.copy(navHideAfter = v) } } }
        viewModelScope.launch { prefs.ambientLight.collect { v -> _uiState.update { it.copy(ambientLight = v) } } }
        viewModelScope.launch { prefs.shortsLength.collect { v -> _uiState.update { it.copy(shortsLength = v) } } }
        viewModelScope.launch {
            demo.installed.collect { installed ->
                val bytes = withContext(Dispatchers.IO) { demo.usedBytes() }
                _uiState.update { it.copy(demoInstalled = installed, demoBytes = bytes) }
            }
        }
        viewModelScope.launch { userChapters.stats().collect { c -> _uiState.update { it.copy(userChapters = c) } } }
        viewModelScope.launch { prefs.appLock.collect { v -> _uiState.update { it.copy(appLock = v) } } }
        viewModelScope.launch { prefs.appLockAfter.collect { v -> _uiState.update { it.copy(appLockAfter = v) } } }
        viewModelScope.launch {
            combine(sources.observeServers(), sources.observeEnabledShares()) { servers, shares ->
                shares.mapNotNull { share ->
                    val server = servers.firstOrNull { it.id == share.serverId } ?: return@mapNotNull null
                    // A source on the phone has no share to write to; a switch for it would be a lie.
                    if (LocalSource.isLocal(server.host.host)) return@mapNotNull null
                    ShareWriteRow(share.id, "${share.name} on ${server.name}", share.writeChapters)
                }
            }.collect { rows -> _uiState.update { it.copy(shareWrites = rows) } }
        }
        viewModelScope.launch {
            combine(phone.observeFolders(), prefs.hiddenPhoneFolders) { folders, hidden ->
                folders.filter { it.fileCount > 0 }
                    .map { f -> PhoneFolderRow(f.relPath, f.name, PhonePaths.display(f.relPath), f.fileCount, shown = f.relPath !in hidden) }
                    .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
            }.collect { rows -> _uiState.update { it.copy(phoneFolders = rows) } }
        }
        viewModelScope.launch {
            artwork.observeCount().collect { count ->
                val bytes = withContext(Dispatchers.IO) { artwork.cacheSizeBytes() }
                _uiState.update { it.copy(artworkCount = count, artworkBytes = bytes) }
            }
        }
    }

    /** Settings › Media › Prepare: walk every enabled share and make what is missing. */
    fun prepareArtwork() {
        viewModelScope.launch { sources.observeEnabledShares().first().forEach { prefetcher.enqueue(it.id) } }
    }

    fun stopArtwork() = prefetcher.cancelAll()

    /**
     * Settings › Downloads › Stop. The rows become FAILED · CANCELLED
     * rather than disappearing, so a stopped batch lands in Library's
     * "Failed" section with its "Try again" and the bytes already copied
     * are kept for the resume.
     */
    fun stopDownloads() {
        viewModelScope.launch { transfers.cancelAll() }
    }

    fun scanAll() {
        viewModelScope.launch { scans.scanAll() }
    }

    /** One share's own Scan button. [ScanRepository.scanAll] already filters by server. */
    fun scan(serverId: Long) {
        viewModelScope.launch { scans.scanAll(serverId) }
    }

    /**
     * Open (or close) the "Disconnect X?" confirm.
     *
     * The count of copies it would keep is fetched after the dialog is
     * already up rather than before, so opening it never waits on a query;
     * the sentence fills in. The guard on the way back matters — the user
     * can dismiss and open another server's dialog while this is in flight.
     */
    fun askDisconnect(row: ServerRow?) {
        _uiState.update { it.copy(confirmDisconnect = row, confirmDisconnectKeeps = 0) }
        val target = row ?: return
        viewModelScope.launch {
            val keeps = deviceLibrary.downloadedCount(target.serverId)
            _uiState.update { if (it.confirmDisconnect?.serverId == target.serverId) it.copy(confirmDisconnectKeeps = keeps) else it }
        }
    }

    /** Tapping a server's name opens the rename dialog; null closes it. */
    fun askRename(row: ServerRow?) = _uiState.update { it.copy(renaming = row) }

    /**
     * Store what the user called this server. Nothing is keyed to a
     * server's name, so this is a single column write; the rows redraw
     * because they are observing the `servers` table, not because anything
     * is told to refresh. A blank name is not an error — it puts the
     * derived name ("TOWER", or the address) back.
     */
    fun rename(row: ServerRow, name: String) {
        _uiState.update { it.copy(renaming = null) }
        viewModelScope.launch { sources.renameServer(row.serverId, name) }
    }

    /** Settings › Chapters › Clear: asks first, because there is no getting them back. */
    fun askClearChapters(open: Boolean) = _uiState.update { it.copy(confirmClearChapters = open) }

    /**
     * Re-read what the device can do about biometrics. Called when Settings
     * appears, because the answer changes while the app is in the
     * background: enrolling a fingerprint happens in the system settings,
     * and coming back to a stale "no fingerprint set up" would be wrong.
     */
    fun refreshBiometrics() {
        _uiState.update { it.copy(biometrics = biometrics.availability()) }
    }

    /**
     * Turning the app lock ON asks for the prompt first — proof the reader
     * works and that the person switching it on is the person who can get
     * back in. Turning it OFF does not: they are already past the lock.
     *
     * The Activity is a parameter rather than something this holds: the
     * system prompt needs a window, and a ViewModel that kept one would
     * leak it on every rotation.
     */
    fun setAppLock(activity: Activity, enabled: Boolean) {
        if (!enabled) {
            viewModelScope.launch { prefs.setAppLock(false) }
            return
        }
        viewModelScope.launch {
            val result = biometrics.authenticate(activity, title = "Lock Regolith", subtitle = "Check it works before you rely on it.")
            if (result is AuthResult.Success) prefs.setAppLock(true)
        }
    }

    fun setAppLockAfter(after: LockAfter) = viewModelScope.launch { prefs.setAppLockAfter(after) }.let { }

    /** Settings › Chapters: whether chapter files are written to one share (P10). */
    /** Off keeps a phone folder out of Library, Search and Continue watching. Nothing on the phone is touched. */
    fun setPhoneFolderShown(relPath: String, shown: Boolean) = viewModelScope.launch { phone.setFolderHidden(relPath, hidden = !shown) }.let { }

    fun setShareWriteChapters(shareId: Long, enabled: Boolean) = viewModelScope.launch { sources.setShareWriteChapters(shareId, enabled) }.let { }

    /** Every chapter kept on this phone. The share is never touched from here; films with a file there get theirs back at the next scan. */
    fun clearChapters() {
        _uiState.update { it.copy(confirmClearChapters = false) }
        viewModelScope.launch { userChapters.clearAll() }
    }

    /**
     * "The media list is removed from this device. Nothing on the share is
     * touched." Copies already on the phone are adopted into the device
     * source first, so they outlive the server ([DeviceLibrary]).
     */
    fun disconnect(row: ServerRow) {
        _uiState.update { it.copy(confirmDisconnect = null, confirmDisconnectKeeps = 0) }
        viewModelScope.launch { sources.removeServer(row.serverId) }
    }

    fun setHardwareDecoding(enabled: Boolean) = viewModelScope.launch { prefs.setHardwareDecoding(enabled) }.let { }
    fun setScrubThumbnails(enabled: Boolean) = viewModelScope.launch { prefs.setScrubThumbnails(enabled) }.let { }
    fun setAutoplayNext(enabled: Boolean) = viewModelScope.launch { prefs.setAutoplayNext(enabled) }.let { }
    fun setAutoplayImmediately(enabled: Boolean) = viewModelScope.launch { prefs.setAutoplayImmediately(enabled) }.let { }
    fun setAutoHideRail(enabled: Boolean) = viewModelScope.launch { prefs.setAutoHideRail(enabled) }.let { }

    /** Settings › Display › Hide after: how long the navigation waits before it goes. */
    fun setNavHideAfter(after: NavHideAfter) = viewModelScope.launch { prefs.setNavHideAfter(after) }.let { }

    fun setAmbientLight(enabled: Boolean) = viewModelScope.launch { prefs.setAmbientLight(enabled) }.let { }

    /** Settings › Shorts: the longest a clip may be and still reach the feed. */
    fun setShortsLength(length: ShortsLength) = viewModelScope.launch { prefs.setShortsLength(length) }.let { }

    /**
     * Settings › Demo library. Installing replaces whatever was there, so
     * the button is safe to press twice; removing deletes the demo server
     * (its shares, folders and files cascade) and the clips.
     */
    fun toggleDemoLibrary() {
        if (_uiState.value.demoWorking) return
        val installed = _uiState.value.demoInstalled
        viewModelScope.launch {
            _uiState.update { it.copy(demoWorking = true) }
            if (installed) demo.remove() else demo.install()
            _uiState.update { it.copy(demoWorking = false, demoBytes = withContext(Dispatchers.IO) { demo.usedBytes() }) }
        }
    }

    /** Settings › Media › Clear: forget every cached image and re-read on demand. */
    fun clearArtwork() {
        viewModelScope.launch {
            _uiState.update { it.copy(clearing = true) }
            withContext(Dispatchers.IO) { artwork.clearAll() }
            imageLoader.memoryCache?.clear()
            _uiState.update { it.copy(clearing = false, artworkBytes = 0) }
        }
    }
}
