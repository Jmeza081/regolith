package com.regolith.ui.shorts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import com.regolith.data.artwork.ArtworkPrefetcher
import com.regolith.data.prefs.AppPreferences
import com.regolith.data.repository.LibraryRepository
import com.regolith.data.repository.SourceRepository
import com.regolith.data.transfer.TransferRepository
import com.regolith.domain.playback.VideoInfo
import com.regolith.player.PlaybackSession
import com.regolith.player.ShortsPlayerPool
import com.regolith.ui.util.formatDurationShort
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The Shorts feed: every vertical clip under a minute, from every enabled
 * share, newest first.
 *
 * It owns a [ShortsPlayerPool] rather than driving [PlaybackSession],
 * which is the scoped exception to guardrail G4 recorded in
 * `docs/ARCHITECTURE.md`. Because the pool is not a singleton it is created
 * with this ViewModel and released in [onCleared], so the feed never leaves
 * three decoders behind it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@UnstableApi
@HiltViewModel
class ShortsViewModel @Inject constructor(
    private val library: LibraryRepository,
    private val sources: SourceRepository,
    private val transfers: TransferRepository,
    private val prefs: AppPreferences,
    private val session: PlaybackSession,
    val pool: ShortsPlayerPool,
    artwork: ArtworkPrefetcher,
) : ViewModel() {

    /**
     * Bumped after every [bind] so the pager recomposes and picks up the
     * players it has just been given. An ExoPlayer is not observable and
     * the pool hands back a different one per page, so there has to be
     * something for Compose to watch.
     */
    private val _bindVersion = MutableStateFlow(0)
    val bindVersion: StateFlow<Int> = _bindVersion

    private val shorts = sources.observeEnabledShares()
        .flatMapLatest { shares -> if (shares.isEmpty()) flowOf(emptyList()) else library.observeShorts(shares.map { it.id }) }

    val uiState: StateFlow<ShortsUiState> = combine(
        shorts, transfers.observeDoneFileIds(), artwork.observe(),
    ) { files, onDevice, walk ->
        ShortsUiState(
            loaded = true,
            items = files.map { f ->
                ShortItem(
                    fileId = f.id,
                    name = f.name.substringBeforeLast('.'),
                    folderLabel = f.relPath.substringBeforeLast('/', "").substringAfterLast('/').ifEmpty { "This share" },
                    meta = listOfNotNull(
                        f.durationMs?.takeIf { it > 0 }?.let { formatDurationShort(it) },
                        VideoInfo.resolutionLabelFor(f.width, f.height).ifEmpty { null },
                    ).joinToString(" · "),
                    folderId = f.folderId,
                    onDevice = f.id in onDevice,
                )
            },
            // Only while something is actually walking: a finished walk that
            // found no vertical clips must read as "none", not as "wait".
            measuringLine = if (walk.running && walk.total > 0) "%,d of %,d files checked".format(walk.done, walk.total) else null,
            measuringFraction = walk.fraction,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ShortsUiState())

    init {
        // A film may still be playing behind the tabs (G4 keeps the session
        // alive when its screen is gone). Pause rather than stop: the film
        // keeps its place and its queue, and four decoders is well inside
        // what a phone will give us.
        session.pause()
    }

    /** Make [index] the clip on screen, with its neighbours warm. */
    fun bind(index: Int) {
        val items = uiState.value.items
        if (index !in items.indices) return
        viewModelScope.launch {
            pool.bind(index, items.map { it.fileId }, prefs.hardwareDecoding.first())
            _bindVersion.value += 1
        }
    }

    fun togglePlayPause() = pool.togglePlayPause()

    fun holdFast(hold: Boolean) = pool.holdFast(hold)

    fun keepOnDevice(fileId: Long) {
        viewModelScope.launch { transfers.start(fileId) }
    }

    override fun onCleared() {
        pool.releaseAll()
    }
}
