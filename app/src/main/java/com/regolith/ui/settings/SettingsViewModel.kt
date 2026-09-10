package com.regolith.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil3.ImageLoader
import com.regolith.data.artwork.ArtworkRepository
import com.regolith.data.db.ScanRunEntity
import com.regolith.data.demo.DemoLibrary
import com.regolith.data.prefs.AppPreferences
import com.regolith.data.repository.SourceRepository
import com.regolith.data.scan.ScanRepository
import com.regolith.ui.util.formatBytes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
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
) : ViewModel() {
    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val shares = sources.observeEnabledShares()
            val runs = shares.flatMapLatest { list -> if (list.isEmpty()) flowOf(emptyList()) else scans.observeLatest(list.map { it.id }) }
            combine(sources.observeServers(), shares, runs) { servers, shareList, runList ->
                servers.map { server ->
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
                    ServerRow(server.id, server.name, status, running.isNotEmpty(), reachable = reachable, showing = reachable && own.isNotEmpty())
                }
            }.collect { rows -> _uiState.update { it.copy(servers = rows) } }
        }
        viewModelScope.launch { prefs.hardwareDecoding.collect { v -> _uiState.update { it.copy(hardwareDecoding = v) } } }
        viewModelScope.launch { prefs.scrubThumbnails.collect { v -> _uiState.update { it.copy(scrubThumbnails = v) } } }
        viewModelScope.launch { prefs.autoplayNext.collect { v -> _uiState.update { it.copy(autoplayNext = v) } } }
        viewModelScope.launch { prefs.autoplayImmediately.collect { v -> _uiState.update { it.copy(autoplayImmediately = v) } } }
        viewModelScope.launch { prefs.autoHideRail.collect { v -> _uiState.update { it.copy(autoHideRail = v) } } }
        viewModelScope.launch { prefs.movingTiles.collect { v -> _uiState.update { it.copy(movingTiles = v) } } }
        viewModelScope.launch {
            demo.installed.collect { installed ->
                val bytes = withContext(Dispatchers.IO) { demo.usedBytes() }
                _uiState.update { it.copy(demoInstalled = installed, demoBytes = bytes) }
            }
        }
        viewModelScope.launch {
            artwork.observeCount().collect { count ->
                val bytes = withContext(Dispatchers.IO) { artwork.cacheSizeBytes() }
                _uiState.update { it.copy(artworkCount = count, artworkBytes = bytes) }
            }
        }
    }

    fun scanAll() {
        viewModelScope.launch { scans.scanAll() }
    }

    /** One share's own Scan button. [ScanRepository.scanAll] already filters by server. */
    fun scan(serverId: Long) {
        viewModelScope.launch { scans.scanAll(serverId) }
    }

    fun askDisconnect(row: ServerRow?) = _uiState.update { it.copy(confirmDisconnect = row) }

    /** "The media list is removed from this device. Nothing on the share is touched." */
    fun disconnect(row: ServerRow) {
        _uiState.update { it.copy(confirmDisconnect = null) }
        viewModelScope.launch { sources.removeServer(row.serverId) }
    }

    fun setHardwareDecoding(enabled: Boolean) = viewModelScope.launch { prefs.setHardwareDecoding(enabled) }.let { }
    fun setScrubThumbnails(enabled: Boolean) = viewModelScope.launch { prefs.setScrubThumbnails(enabled) }.let { }
    fun setAutoplayNext(enabled: Boolean) = viewModelScope.launch { prefs.setAutoplayNext(enabled) }.let { }
    fun setAutoplayImmediately(enabled: Boolean) = viewModelScope.launch { prefs.setAutoplayImmediately(enabled) }.let { }
    fun setAutoHideRail(enabled: Boolean) = viewModelScope.launch { prefs.setAutoHideRail(enabled) }.let { }
    fun setMovingTiles(enabled: Boolean) = viewModelScope.launch { prefs.setMovingTiles(enabled) }.let { }

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
