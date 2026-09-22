package com.regolith.ui.serverdetail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.regolith.data.artwork.ArtworkPrefetcher
import com.regolith.data.db.ScanRunEntity
import com.regolith.data.repository.AddAddressResult
import com.regolith.data.repository.DeviceLibrary
import com.regolith.data.repository.LibraryRepository
import com.regolith.data.repository.SourceRepository
import com.regolith.data.scan.ScanRepository
import com.regolith.domain.model.AddressMode
import com.regolith.domain.model.ServerAddress
import com.regolith.domain.smb.SmbAddressParser
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * One server's own page.
 *
 * Assisted injection, like `LibraryViewModel`: the ViewModel is created
 * for a particular `serverId`, so nothing here has to carry "which server"
 * through every call.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel(assistedFactory = ServerDetailViewModel.Factory::class)
class ServerDetailViewModel @AssistedInject constructor(
    @Assisted private val serverId: Long,
    private val sources: SourceRepository,
    private val library: LibraryRepository,
    private val scans: ScanRepository,
    private val prefetcher: ArtworkPrefetcher,
    private val deviceLibrary: DeviceLibrary,
) : ViewModel() {

    @AssistedFactory
    interface Factory {
        fun create(serverId: Long): ServerDetailViewModel
    }

    private val _uiState = MutableStateFlow(ServerDetailUiState())
    val uiState: StateFlow<ServerDetailUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val shares = sources.observeShares(serverId)
            val runs = shares.flatMapLatest { list ->
                if (list.isEmpty()) flowOf(emptyList()) else scans.observeLatest(list.map { it.id })
            }
            val files = shares.flatMapLatest { list ->
                if (list.isEmpty()) flowOf(0) else library.observeFileCountInShares(list.filter { it.enabled }.map { it.id })
            }
            combine(
                sources.observeServers(),
                sources.observeAddresses(serverId),
                shares,
                runs,
                files,
            ) { servers, addresses, shareList, runList, fileCount ->
                val server = servers.firstOrNull { it.id == serverId }
                val running = runList.filter { it.status == ScanRunEntity.RUNNING }
                ServerDetailUiState(
                    loaded = server != null,
                    name = server?.name.orEmpty(),
                    mode = server?.addressMode ?: AddressMode.AUTO,
                    rows = addresses.map { address ->
                        val inUse = server != null && address.host == server.host
                        AddressRow(address = address, inUse = inUse, pinned = inUse && server?.addressMode == AddressMode.PINNED)
                    },
                    shareCount = shareList.size,
                    enabledShareCount = shareList.count { it.enabled },
                    fileCount = fileCount,
                    lastScanAtMs = shareList.mapNotNull { it.lastScanAtMs }.maxOrNull(),
                    scanning = running.isNotEmpty(),
                    scanFilesFound = running.sumOf { it.filesFound },
                    unreachable = server?.unreachableSinceMs != null,
                )
            }.collect { built ->
                // Onto the live state, so an open dialog is not closed by a
                // scan tick arriving underneath it.
                _uiState.update { live ->
                    built.copy(
                        renaming = live.renaming,
                        addingAddress = live.addingAddress,
                        labelling = live.labelling,
                        confirmRemove = live.confirmRemove,
                        confirmDisconnect = live.confirmDisconnect,
                        confirmDisconnectKeeps = live.confirmDisconnectKeeps,
                        message = live.message,
                        artworkRunning = live.artworkRunning,
                        artworkDone = live.artworkDone,
                        artworkTotal = live.artworkTotal,
                    )
                }
            }
        }
        viewModelScope.launch {
            prefetcher.observe().collect { p ->
                _uiState.update { it.copy(artworkRunning = p.running, artworkDone = p.done, artworkTotal = p.total) }
            }
        }
    }

    // --- Addresses

    fun askAddAddress(open: Boolean) = _uiState.update { it.copy(addingAddress = open, message = null) }

    fun askLabel(address: ServerAddress?) = _uiState.update { it.copy(labelling = address) }

    fun askRemove(address: ServerAddress?) = _uiState.update { it.copy(confirmRemove = address) }

    fun dismissMessage() = _uiState.update { it.copy(message = null) }

    /**
     * Add a way in, typed the same way the Add Server screen takes one, so
     * "192.168.4.82", "smb://tower/media" and a bare hostname all work.
     */
    fun addAddress(typed: String, label: String) {
        val parsed = SmbAddressParser.parse(typed)
        if (parsed == null) {
            _uiState.update { it.copy(message = "That does not look like an address Regolith can use.") }
            return
        }
        viewModelScope.launch {
            val result = sources.addAddress(serverId, label, parsed)
            _uiState.update {
                when (result) {
                    AddAddressResult.Added -> it.copy(addingAddress = false, message = null)
                    AddAddressResult.AlreadyHere -> it.copy(addingAddress = false, message = "This server already answers to that address.")
                    is AddAddressResult.TakenBy -> it.copy(message = "Another server already uses that address.")
                }
            }
        }
    }

    fun setLabel(address: ServerAddress, label: String) {
        _uiState.update { it.copy(labelling = null) }
        viewModelScope.launch { sources.setAddressLabel(address.id, label) }
    }

    fun removeAddress(address: ServerAddress) {
        _uiState.update { it.copy(confirmRemove = null) }
        viewModelScope.launch {
            if (!sources.removeAddress(address.id)) {
                _uiState.update { it.copy(message = "A server needs at least one way in.") }
            }
        }
    }

    /** Pin this one, or — when it is already pinned — go back to measuring. */
    fun chooseAddress(row: AddressRow) {
        viewModelScope.launch {
            if (row.pinned) sources.useFastestAddress(serverId) else sources.pinAddress(serverId, row.address.id)
        }
    }

    fun useFastest() = viewModelScope.launch { sources.useFastestAddress(serverId) }.let { }

    // --- Name, library, disconnect

    fun askRename(open: Boolean) = _uiState.update { it.copy(renaming = open) }

    fun rename(typed: String) {
        _uiState.update { it.copy(renaming = false) }
        viewModelScope.launch { sources.renameServer(serverId, typed) }
    }

    fun scanNow() = viewModelScope.launch { scans.scanAll(serverId) }.let { }

    fun stopScan() = viewModelScope.launch {
        sources.observeShares(serverId).first().forEach { scans.cancel(it.id) }
    }.let { }

    fun prepareArtwork() = viewModelScope.launch {
        sources.observeShares(serverId).first().filter { it.enabled }.forEach { prefetcher.enqueue(it.id) }
    }.let { }

    fun stopArtwork() = prefetcher.cancelAll()

    fun askDisconnect(open: Boolean) {
        _uiState.update { it.copy(confirmDisconnect = open, confirmDisconnectKeeps = 0) }
        if (!open) return
        viewModelScope.launch {
            val keeps = deviceLibrary.downloadedCount(serverId)
            _uiState.update { if (it.confirmDisconnect) it.copy(confirmDisconnectKeeps = keeps) else it }
        }
    }

    fun disconnect(onDone: () -> Unit) {
        _uiState.update { it.copy(confirmDisconnect = false) }
        viewModelScope.launch {
            sources.removeServer(serverId)
            onDone()
        }
    }
}
