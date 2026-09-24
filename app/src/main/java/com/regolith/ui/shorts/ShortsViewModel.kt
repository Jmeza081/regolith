package com.regolith.ui.shorts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import com.regolith.data.artwork.ArtworkPrefetcher
import com.regolith.data.db.MediaFileEntity
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
import kotlin.random.Random

/**
 * The Shorts feed: every vertical clip under a minute, from every enabled
 * share (or one folder), in a shuffled order unless you turn that off.
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

    /**
     * Whether a finger is holding the right half down for 2x.
     *
     * A flow of its own rather than a field on [ShortsUiState], for the same
     * reason [bindVersion] is: the feed's state is assembled by combining
     * the library, the folder pick and the artwork walk, and a value that
     * flips twice per gesture has no business rebuilding that. It mirrors
     * what `PlaybackSession` already keeps for the full player, so both
     * screens can say the same sentence about the same gesture.
     */
    private val _holdingFast = MutableStateFlow(false)
    val holdingFast: StateFlow<Boolean> = _holdingFast

    /**
     * Auto-advance, from preferences so it survives leaving the feed.
     *
     * Kept OUT of [uiState] deliberately: that combine is already at the
     * five-flow overload, and this is a mode rather than content — the
     * pager reads it when a clip wraps, and nothing else re-renders on it.
     */
    val autoAdvance: StateFlow<Boolean> = prefs.shortsAutoAdvance
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** null means every folder. */
    private val _folderId = MutableStateFlow<Long?>(null)

    /**
     * The shuffle, as a seed rather than a boolean: a seed makes the order
     * STABLE across every re-emission of the feed (a download finishing, the
     * walk measuring one more file), where `shuffled()` on each pass would
     * reorder the deck under a finger already swiping it.
     *
     * Starts ON: newest-first always opened on the same clip. This ViewModel
     * lives as long as the Shorts tab is on the back stack, so every arrival
     * at the tab is a fresh deck.
     */
    private val _shuffleSeed = MutableStateFlow<Long?>(System.nanoTime())

    // The length is folded in UPSTREAM rather than into `uiState`: it changes
    // which files qualify, not how they are drawn, so it belongs in the query.
    // (It also could not go in the combine below, which is already at the
    // five-flow overload.)
    private val shorts = combine(sources.observeEnabledShares(), prefs.shortsLength) { shares, length -> shares to length }
        .flatMapLatest { (shares, length) ->
            if (shares.isEmpty()) flowOf(emptyList()) else library.observeShorts(shares.map { it.id }, length.maxMs)
        }

    val uiState: StateFlow<ShortsUiState> = combine(
        shorts, transfers.observeDoneFileIds(), artwork.observe(), _folderId, _shuffleSeed,
    ) { files, onDevice, walk, folderId, seed ->
        val all = files.map { it.toItem(it.id in onDevice) }
        // Offered folders come from ALL shorts, never the filtered list, or
        // picking one would leave the sheet with a single way out.
        val folders = all.groupBy { it.folderId }
            .map { (id, group) -> ShortsFolder(id, group.first().folderLabel, group.size) }
            .sortedBy { it.label.lowercase() }
        val picked = if (folderId == null) all else all.filter { it.folderId == folderId }
        ShortsUiState(
            loaded = true,
            items = if (seed == null) picked else picked.shuffled(Random(seed)),
            folders = folders,
            folderId = folderId.takeIf { id -> folders.any { it.id == id } },
            shuffled = seed != null,
            shuffleSeed = seed,
            // Only while something is actually walking: a finished walk that
            // found no vertical clips must read as "none", not as "wait".
            measuringLine = if (walk.running && walk.total > 0) "%,d of %,d files checked".format(walk.done, walk.total) else null,
            measuringFraction = walk.fraction,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ShortsUiState())

    private fun MediaFileEntity.toItem(onDevice: Boolean) = ShortItem(
        fileId = id,
        name = name.substringBeforeLast('.'),
        folderLabel = relPath.substringBeforeLast('/', "").substringAfterLast('/').ifEmpty { "This share" },
        meta = listOfNotNull(
            durationMs?.takeIf { it > 0 }?.let { formatDurationShort(it) },
            VideoInfo.resolutionLabelFor(width, height).ifEmpty { null },
        ).joinToString(" · "),
        folderId = folderId,
        onDevice = onDevice,
    )

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

    fun holdFast(hold: Boolean) {
        _holdingFast.value = hold
        pool.holdFast(hold)
    }

    /** Leaving the screen: silence without giving up the prepared window. */
    fun pauseAll() {
        // A hold is released by `tryAwaitRelease`, which never arrives if the
        // gesture layer is disposed under the finger -- swiping to another
        // tab mid-hold, say. Clearing it here means the label cannot be left
        // on screen describing a speed nothing is playing at.
        _holdingFast.value = false
        pool.pauseAll()
    }

    /** [folderId] null plays everything again. */
    fun pickFolder(folderId: Long?) {
        _folderId.value = folderId
    }

    /** Off, or on with a fresh order — asking to shuffle again should reshuffle. */
    fun toggleShuffle() {
        _shuffleSeed.value = if (_shuffleSeed.value == null) System.nanoTime() else null
    }

    /**
     * A new deck: shuffle on, fresh order. Tapping the Shorts tab while
     * already on it asks for this, so the same clip is not always first.
     */
    fun reshuffle() {
        _shuffleSeed.value = System.nanoTime()
    }

    fun toggleAutoAdvance() {
        viewModelScope.launch { prefs.setShortsAutoAdvance(!autoAdvance.value) }
    }

    fun keepOnDevice(fileId: Long) {
        viewModelScope.launch { transfers.start(fileId) }
    }

    override fun onCleared() {
        pool.releaseAll()
    }
}
