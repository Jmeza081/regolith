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

    private val resume = library.observeContinueWatching(RESUME_LIMIT)
        .flatMapLatest { files -> library.observeProgress(files.map { it.id }).map2(files) }
    private val shares = sources.observeEnabledShares()
    private val runs = shares.flatMapLatest { list -> if (list.isEmpty()) flowOf(emptyList()) else scans.observeLatest(list.map { it.id }) }

    private val downloads = transfers.observeAll()

    val uiState: StateFlow<HomeUiState> = combine(
        combine(sources.observeServers(), shares, ::Pair), resume, library.observeNewest(NEW_LIMIT), runs, downloads,
    ) { (servers, shareList), resumeItems, newest, runList, downloadRows ->
        val running = runList.filter { it.status == ScanRunEntity.RUNNING }
        val ready = downloadRows.filter { it.status == TransferStatus.DONE.name }
        HomeUiState(
            downloadsReady = ready.size,
            downloadsBytes = ready.sumOf { it.totalBytes },
            loaded = true,
            serverNames = servers.map { it.name },
            resume = resumeItems,
            newlyAdded = newest.map { f ->
                val parsed = ParsedName(f.titleParsed ?: f.name.substringBeforeLast('.'), f.year, f.season, f.episode)
                NewItem(
                    fileId = f.id,
                    name = if (parsed.matched) parsed.display else f.name.substringBeforeLast('.'),
                    artwork = ArtworkRequest(ArtworkOwner.File(f.id), ArtworkKind.POSTER),
                    meta = listOfNotNull(VideoInfo.resolutionLabelFor(f.width, f.height).ifEmpty { null }, formatWhen(f.addedAtMs)).joinToString(" · "),
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

    private fun kotlinx.coroutines.flow.Flow<List<com.regolith.data.db.PlaybackProgressEntity>>.map2(files: List<MediaFileEntity>) =
        kotlinx.coroutines.flow.flow {
            collect { progress ->
                val byId = progress.associateBy { it.fileId }
                emit(
                    files.mapNotNull { f ->
                        val p = byId[f.id] ?: return@mapNotNull null
                        val parsed = ParsedName(f.titleParsed ?: f.name.substringBeforeLast('.'), f.year, f.season, f.episode)
                        ResumeItem(
                            fileId = f.id,
                            name = if (parsed.matched) parsed.display else f.name.substringBeforeLast('.'),
                            artwork = ArtworkRequest(ArtworkOwner.File(f.id), ArtworkKind.THUMB),
                            positionMs = p.positionMs,
                            durationMs = p.durationMs,
                            meta = listOfNotNull(VideoInfo.resolutionLabelFor(f.width, f.height).ifEmpty { null }, formatWhen(p.updatedAtMs)).joinToString(" · "),
                        )
                    },
                )
            }
        }

    private companion object {
        const val RESUME_LIMIT = 10
        const val NEW_LIMIT = 12
    }
}
