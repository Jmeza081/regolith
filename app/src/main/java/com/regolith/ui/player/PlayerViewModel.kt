package com.regolith.ui.player

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.regolith.data.prefs.AppPreferences
import com.regolith.data.repository.UserChapterRepository
import com.regolith.data.transfer.TransferRepository
import com.regolith.data.transfer.TransferRepository.Companion.causeEnum
import com.regolith.data.transfer.TransferRepository.Companion.statusEnum
import com.regolith.domain.playback.AbLoop
import com.regolith.domain.playback.ChapterDraft
import com.regolith.ui.titledetail.TransferView
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import com.regolith.domain.playback.PlayerOrientation
import com.regolith.player.PlaybackSession
import com.regolith.player.PlaybackState
import com.regolith.ui.navigation.RegolithKey
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.stateIn
import kotlin.math.absoluteValue

/**
 * Thin adapter between the Player screen and the app-owned
 * [PlaybackSession]. It holds no playback state of its own (guardrail G4).
 * Playback stops when the screen goes away; background audio and PiP will
 * change that later without touching the screen.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@UnstableApi
@HiltViewModel(assistedFactory = PlayerViewModel.Factory::class)
class PlayerViewModel @AssistedInject constructor(
    @Assisted private val key: RegolithKey.Player,
    private val session: PlaybackSession,
    private val prefs: AppPreferences,
    private val transfers: TransferRepository,
    private val userChapters: UserChapterRepository,
) : ViewModel() {

    @AssistedFactory
    interface Factory {
        fun create(key: RegolithKey.Player): PlayerViewModel
    }

    val state: StateFlow<PlaybackState> = session.state
    val player: StateFlow<ExoPlayer?> = session.player
    val scrubThumbnails: StateFlow<Boolean> = prefs.scrubThumbnails.stateIn(viewModelScope, SharingStarted.Eagerly, true)

    /** Settings › Playback › Keep playing, mirrored in the playback sheet. */
    val autoplayNext: StateFlow<Boolean> = prefs.autoplayNext.stateIn(viewModelScope, SharingStarted.Eagerly, true)

    fun setAutoplayNext(enabled: Boolean) = viewModelScope.launch { prefs.setAutoplayNext(enabled) }.let { }

    /** The player's rotation lock, from the playback sheet. */
    val orientation: StateFlow<PlayerOrientation> = prefs.playerOrientation.stateIn(viewModelScope, SharingStarted.Eagerly, PlayerOrientation.AUTO)

    fun setOrientation(orientation: PlayerOrientation) = viewModelScope.launch { prefs.setPlayerOrientation(orientation) }.let { }

    /** Settings › Playback › Don't ask first: no card, no countdown, the next file simply starts. */
    val autoplayImmediately: StateFlow<Boolean> = prefs.autoplayImmediately.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun setAutoplayImmediately(enabled: Boolean) = viewModelScope.launch { prefs.setAutoplayImmediately(enabled) }.let { }

    /** Null until read; false shows the gesture map once. */
    val gesturesSeen: StateFlow<Boolean?> = prefs.gesturesSeen.map<Boolean, Boolean?> { it }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun dismissGestureMap() = viewModelScope.launch { prefs.setGesturesSeen() }.let { }

    /** Where the finger is on the timeline, or null when not scrubbing. */
    private val scrubMs = MutableStateFlow<Long?>(null)

    /** The preview frame for the current scrub position; re-evaluated as frames arrive. */
    val scrubFrame: StateFlow<Bitmap?> = session.scrubThumbnails
        .flatMapLatest { thumbs -> combine(scrubMs, thumbs.updates) { ms, _ -> ms?.let { thumbs.nearest(it) } } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Settings › Display › Ambient light: the live wash behind the picture. */
    val ambientLight: StateFlow<Boolean> = prefs.ambientLight.stateIn(viewModelScope, SharingStarted.Eagerly, true)

    /**
     * The filmstrip under the hinge in flex mode: [STRIP_FRAMES] frames, one
     * per equal slice of the film, taken at the middle of each slice so the
     * first is not the black frame every film opens on.
     *
     * Same pipeline as the scrub preview -- ask for a position, redraw as
     * frames land -- so a strip costs nine key-frame seeks and no new
     * machinery. Nothing is asked for until [requestStrip] is called, which
     * only the flex layout does.
     */
    val strip: StateFlow<List<StripFrame>> = session.scrubThumbnails
        .flatMapLatest { thumbs ->
            combine(state, thumbs.updates) { s, _ ->
                stripPositions(s.durationMs).map { ms -> StripFrame(ms, thumbs.nearest(ms)) }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * A frame for every chapter, through the same pipeline as the filmstrip:
     * ask for a position, redraw as the pictures land. The chapter sheet is
     * a wall of stills rather than a list of times, and this is what fills
     * it — one key-frame seek per row, and no new machinery.
     *
     * Frames are taken a little INTO each part, not at its first frame: a
     * chapter boundary is usually a cut, and the frame on a cut is often
     * black or a title card. The same reason the artwork grab moved off 10%.
     */
    val chapterFrames: StateFlow<Map<Long, Bitmap?>> = session.scrubThumbnails
        .flatMapLatest { thumbs ->
            combine(state, thumbs.updates) { s, _ ->
                s.chapters.associate { it.startMs to thumbs.nearest(frameFor(it.startMs, s)) }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /**
     * Fill the chapter sheet, the part you are in first. One batch, not a
     * loop of single requests: the single-request path keeps only the latest
     * position, which is right for a finger and wrong for a wall — it is why
     * most chapters used to stay dark.
     */
    fun requestChapterFrames() {
        val thumbs = session.scrubThumbnails.value
        val s = state.value
        val here = s.positionMs
        thumbs.requestAll(s.chapters.map { frameFor(it.startMs, s) }.sortedBy { (it - here).absoluteValue })
    }

    private fun frameFor(startMs: Long, s: PlaybackState): Long {
        val end = s.chapters.firstOrNull { it.startMs > startMs }?.startMs ?: s.durationMs
        val into = ((end - startMs) * CHAPTER_FRAME_FRACTION).toLong()
        return (startMs + into).coerceIn(0L, (s.durationMs - 1).coerceAtLeast(0L))
    }

    /**
     * Load the filmstrip, nearest the playhead first: the frames beside where
     * you are are the ones you are about to look at. A batch, served in order.
     */
    fun requestStrip() {
        val thumbs = session.scrubThumbnails.value
        val here = state.value.positionMs
        thumbs.requestAll(stripPositions(state.value.durationMs).sortedBy { (it - here).absoluteValue })
    }

    // --- Things the screen used to keep for itself, and lost between layouts.

    /**
     * The brightness the left-edge drag set, or null for the system's own.
     * Held here rather than in the screen because the screen re-runs its
     * window setup every time the layout changes (inline to full screen, a
     * rotation) and the value was going back to the system default with it.
     * The player's window is the only thing it applies to; leaving the
     * player lets it go.
     */
    val brightness: StateFlow<Float?> get() = _brightness
    private val _brightness = MutableStateFlow<Float?>(null)

    fun setBrightness(fraction: Float) {
        _brightness.value = fraction.coerceIn(0.01f, 1f)
    }

    /** Settings › Display › Ambient light, also switchable from the playback sheet. */
    fun setAmbientLight(enabled: Boolean) = viewModelScope.launch { prefs.setAmbientLight(enabled) }.let { }

    /**
     * The download of the file on screen, for the pill beside the settings
     * glyph. Follows [PlaybackState.fileId] so autoplay moving to the next
     * episode moves the pill with it; null for a film another app handed
     * over, which has no row to keep.
     */
    val transfer: StateFlow<TransferView?> = state.map { it.fileId }.distinctUntilChanged()
        .flatMapLatest { id ->
            if (id == null || id == RegolithKey.Player.EXTERNAL) flowOf(null) else transfers.observeForFile(id)
        }
        .map { row -> row?.let { TransferView(it.statusEnum(), it.bytesDone, it.totalBytes, it.causeEnum(), it.causeBytes) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Start (or retry) copying the current file to this device. */
    fun keepOnDevice() {
        val id = state.value.fileId?.takeIf { it != RegolithKey.Player.EXTERNAL } ?: return
        viewModelScope.launch { transfers.start(id) }
    }

    /** Cancel a copy in flight, or remove the finished one. The share is untouched. */
    fun removeFromDevice() {
        val id = state.value.fileId?.takeIf { it != RegolithKey.Player.EXTERNAL } ?: return
        viewModelScope.launch { transfers.remove(id) }
    }

    // --- The chapter editor (P9)

    /**
     * The chapters being edited, or null when the editor is closed. Editing
     * state, not playback state, so it belongs here rather than in the
     * session (G4) — and it survives a rotation, which is why it is not a
     * `remember` in the screen. It is thrown away if the film changes
     * underneath it (autoplay), because a draft belongs to one file.
     */
    val chapterDraft: StateFlow<ChapterDraft?> get() = _chapterDraft
    private val _chapterDraft = MutableStateFlow<ChapterDraft?>(null)

    /** True when the film on screen can carry chapters: it has a library row, and its runtime is known. */
    fun canEditChapters(): Boolean {
        val s = state.value
        return s.fileId != null && s.fileId != RegolithKey.Player.EXTERNAL && s.chaptersReady
    }

    /**
     * Open the editor on the chapters showing now — the even split, the
     * file's own, or a previous edit — and pause, so the playhead holds
     * still for marking.
     */
    fun beginChapterEdit() {
        if (!canEditChapters()) return
        val s = state.value
        if (s.isPlaying) session.togglePlayPause()
        _chapterDraft.value = ChapterDraft.seed(s.fileId!!, s.chapters, s.durationMs)
    }

    fun markChapterAtPlayhead() = editDraft { it.mark(state.value.positionMs) }
    fun selectMark(index: Int?) = editDraft { it.select(index) }
    fun moveMark(index: Int, ms: Long) = editDraft { it.move(index, ms) }
    fun nudgeMark(index: Int, deltaMs: Long) = editDraft { it.nudge(index, deltaMs) }
    fun renameMark(index: Int, title: String) = editDraft { it.rename(index, title) }
    fun removeMark(index: Int) = editDraft { it.remove(index) }

    /** Done: the set is written and the editor closes. The session follows the table, so the scrubber updates on its own. */
    fun saveChapters() {
        val draft = _chapterDraft.value ?: return
        _chapterDraft.value = null
        viewModelScope.launch { userChapters.save(draft.fileId, draft.chapters) }
    }

    fun discardChapterEdit() {
        _chapterDraft.value = null
    }

    /** Delete the user's chapters for this film; the file's own markers or the even split come back. */
    fun revertChapters() {
        val id = state.value.fileId?.takeIf { it != RegolithKey.Player.EXTERNAL } ?: return
        viewModelScope.launch { userChapters.clear(id) }
    }

    private inline fun editDraft(edit: (ChapterDraft) -> ChapterDraft) {
        _chapterDraft.value = _chapterDraft.value?.let(edit)
    }

    init {
        // A draft belongs to one file: when autoplay moves on, it goes.
        viewModelScope.launch {
            state.map { it.fileId }.distinctUntilChanged().collect { id ->
                if (_chapterDraft.value?.let { it.fileId != id } == true) _chapterDraft.value = null
            }
        }
        val external = key.externalUri
        if (external != null) {
            session.loadExternal(android.net.Uri.parse(external), key.externalTitle.orEmpty())
        } else {
            session.load(key.fileId, key.startMs, key.queue.ifEmpty { null })
        }
    }

    fun togglePlayPause() = session.togglePlayPause()
    fun seekTo(ms: Long) = session.seekTo(ms)
    fun seekBy(deltaMs: Long) = session.seekBy(deltaMs)
    fun setSpeed(speed: Float) = session.setSpeed(speed)
    fun holdFast(hold: Boolean) = session.holdFast(hold)
    fun setHardwareDecoding(hardware: Boolean) = session.setHardwareDecoding(hardware)
    fun setScrubThumbnails(enabled: Boolean) = session.setScrubThumbnails(enabled)
    /** The repeat button: off -> repeat all -> repeat one -> off. */
    fun cycleRepeat() = session.setRepeat(state.value.repeat.next())

    /** The shuffle button: scramble what is left to play, or put it back in folder order. */
    fun toggleShuffle() = session.setShuffle(!state.value.shuffled)

    fun tapLoopPoint() = session.tapLoopPoint()
    fun nudgeLoopA(deltaMs: Long = AbLoop.NUDGE_MS) = session.nudgeLoopA(deltaMs)
    fun nudgeLoopB(deltaMs: Long = AbLoop.NUDGE_MS) = session.nudgeLoopB(deltaMs)
    fun clearLoop() = session.clearLoop()
    fun playNext(fileId: Long) = session.load(fileId)
    fun onPause() = session.saveProgress()

    /** The scrubber reports where the finger is; ask for that frame. */
    fun onScrub(positionMs: Long) {
        scrubMs.value = positionMs
        session.scrubThumbnails.value.request(positionMs)
    }

    fun onScrubEnd() {
        scrubMs.value = null
    }

    override fun onCleared() {
        session.stop()
    }

    companion object {
        /** How far into a chapter its picture is taken from: past the cut at its start. */
        const val CHAPTER_FRAME_FRACTION = 0.25f

        /** Frames across the flex-mode filmstrip. Nine fits the artboard's deck without crowding. */
        const val STRIP_FRAMES = 9

        /** The middle of each of [STRIP_FRAMES] equal slices, or empty until the duration is known. */
        fun stripPositions(durationMs: Long): List<Long> =
            if (durationMs <= 0) emptyList() else List(STRIP_FRAMES) { i -> (durationMs * (2 * i + 1)) / (2 * STRIP_FRAMES) }
    }
}

/** One frame of the flex-mode filmstrip: where it is in the film, and the picture once it has arrived. */
data class StripFrame(val positionMs: Long, val bitmap: Bitmap?)
