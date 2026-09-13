package com.regolith.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.regolith.data.db.MediaFileEntity
import com.regolith.data.db.ScanRunEntity
import com.regolith.data.repository.LibraryRepository
import com.regolith.data.repository.SourceRepository
import com.regolith.data.scan.ScanRepository
import com.regolith.data.transfer.TransferRepository
import com.regolith.domain.transfer.TransferStatus
import com.regolith.domain.artwork.ArtworkKind
import com.regolith.domain.artwork.ArtworkOwner
import com.regolith.domain.artwork.ArtworkRequest
import com.regolith.domain.library.ParsedName
import com.regolith.domain.playback.VideoInfo
import com.regolith.ui.util.formatWhen
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Home state: servers, the resume row, what arrived, and whether a scan is walking. */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val sources: SourceRepository,
    private val library: LibraryRepository,
    private val scans: ScanRepository,
    transfers: TransferRepository,
) : ViewModel() {

    private val resume = library.observeResume(RESUME_LIMIT)
    private val shares = sources.observeEnabledShares()
    private val runs = shares.flatMapLatest { list -> if (list.isEmpty()) flowOf(emptyList()) else scans.observeLatest(list.map { it.id }) }

    // The finished copies AND the rows behind them: a tile needs a name and
    // a poster, which live on the file, not on the transfer.
    private val downloads = transfers.observeAll()
        .flatMapLatest { rows ->
            val ready = rows.filter { it.status == TransferStatus.DONE.name }
                .sortedByDescending { it.finishedAtMs ?: it.updatedAtMs }
            library.observeFilesByIds(ready.map { it.fileId }).map { files -> ready to files }
        }

    private val newest = library.observeNewest(NEW_LIMIT)
        .flatMapLatest { files -> library.observeProgress(files.map { it.id }).map { p -> files to p } }

    val uiState: StateFlow<HomeUiState> = combine(
        combine(sources.observeServers(), shares, ::Pair), resume, newest, runs, downloads,
    ) { (servers, shareList), resumeItems, (newestFiles, newestProgress), runList, (ready, deviceFiles) ->
        val running = runList.filter { it.status == ScanRunEntity.RUNNING }
        val progressById = newestProgress.associateBy { it.fileId }
        val deviceById = deviceFiles.associateBy { it.id }
        HomeUiState(
            onDevice = ready.mapNotNull { deviceById[it.fileId] }.take(DEVICE_LIMIT).map { f ->
                val parsed = ParsedName(f.titleParsed ?: f.name.substringBeforeLast('.'), f.year, f.season, f.episode)
                NewItem(
                    fileId = f.id,
                    name = if (parsed.matched) parsed.display else f.name.substringBeforeLast('.'),
                    artwork = ArtworkRequest(ArtworkOwner.File(f.id), ArtworkKind.POSTER),
                    meta = VideoInfo.resolutionLabelFor(f.width, f.height),
                    unwatched = false,
                    tag = "home_device",
                )
            },
            downloadsReady = ready.size,
            downloadsBytes = ready.sumOf { it.totalBytes },
            loaded = true,
            serverNames = servers.map { it.name },
            resume = resumeItems,
            newlyAdded = newestFiles.map { f ->
                val parsed = ParsedName(f.titleParsed ?: f.name.substringBeforeLast('.'), f.year, f.season, f.episode)
                val p = progressById[f.id]
                NewItem(
                    fileId = f.id,
                    name = if (parsed.matched) parsed.display else f.name.substringBeforeLast('.'),
                    artwork = ArtworkRequest(ArtworkOwner.File(f.id), ArtworkKind.POSTER),
                    meta = listOfNotNull(VideoInfo.resolutionLabelFor(f.width, f.height).ifEmpty { null }, formatWhen(f.addedAtMs)).joinToString(" · "),
                    unwatched = p == null || (p.positionMs == 0L && !p.completed),
                )
            },
            refreshLine = running.takeIf { it.isNotEmpty() }?.let { "Reading the share · ${"%,d".format(it.sumOf { r -> r.filesFound })} files so far" },
            neverScanned = shareList.isNotEmpty() && shareList.none { it.lastScanAtMs != null } && runList.none { it.status == ScanRunEntity.DONE },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    /** Pull to refresh: every enabled share is walked again. The rows stay tappable meanwhile. */
    fun refresh() {
        viewModelScope.launch { scans.scanAll() }
    }

    private companion object {
        const val RESUME_LIMIT = 10
        /** Films in the "On this device" row. The rest are a tap away on the page itself. */
        const val DEVICE_LIMIT = 12

        const val NEW_LIMIT = 12
    }
}
