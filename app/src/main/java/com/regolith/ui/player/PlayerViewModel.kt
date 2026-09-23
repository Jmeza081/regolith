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
import com.regolith.domain.playback.ChapterWriteOutcome
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import com.regolith.ui.titledetail.TransferView
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import com.regolith.domain.playback.PlayerOrientation
import com.regolith.player.PlaybackSession
import com.regolith.player.ScrubThumbnails
import com.regolith.player.PlaybackState
import com.regolith.ui.navigation.RegolithKey
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import android.os.SystemClock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.stateIn
import kotlin.math.absoluteValue

/**
 * Thin adapter between the Player screen and the app-owned
 * [PlaybackSession]. It holds no playback state of its own (guardrail G4).
 * Playback stops when the screen goes away; background audio and PiP will
 * change that later without touching the screen.
 */
@OptIn(ExperimentalCoroutinesApi::class, kotlinx.coroutines.FlowPreview::class)
@UnstableApi
@HiltViewModel(assistedFactory = PlayerViewModel.Factory::class)
class PlayerViewModel @AssistedInject constructor(
    @Assisted private val key: RegolithKey.Player,
    private val session: PlaybackSession,
    private val prefs: AppPreferences,
    private val transfers: TransferRepository,
    private val phone: com.regolith.data.repository.PhoneLibrary,
    private val userChapters: UserChapterRepository,
    private val posters: com.regolith.data.artwork.PosterRepository,
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
                stripPositions(s.durationMs).map { ms -> StripFrame(ms, thumbs.nearest(ms, exact = true)) }
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
                s.chapters.associate { it.startMs to thumbs.nearest(frameFor(it.startMs, s), exact = true) }
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

    /**
     * Whether the chapters sheet is worth opening yet: the list has settled
     * AND its pictures have (see [chapterFramesSettled], which is where the
     * ways out of waiting live).
     *
     * A poll rather than a combine, because two of the conditions are about
     * time PASSING -- nothing has arrived for a while, or the wait has run
     * long -- and no upstream flow emits for those. It runs only while the
     * player is on screen and stops the moment it settles.
     */
    val chaptersOpenable: StateFlow<Boolean> = state
        .map { it.fileId }
        .distinctUntilChanged()
        .flatMapLatest {
            flow {
                emit(false)
                val startedMs = SystemClock.elapsedRealtime()
                var lastCount = -1
                var lastArrivalMs = startedMs
                while (true) {
                    val s = state.value
                    val present = chapterFrames.value.count { it.value != null }
                    if (present != lastCount) {
                        lastCount = present
                        lastArrivalMs = SystemClock.elapsedRealtime()
                    }
                    val now = SystemClock.elapsedRealtime()
                    val settled = !s.chaptersReady || chapterFramesSettled(
                        chapterCount = s.chapters.size,
                        framesPresent = present,
                        thumbnailsOff = session.scrubThumbnails.value is ScrubThumbnails.None,
                        msSinceRequest = now - startedMs,
                        msSinceArrival = now - lastArrivalMs,
                    )
                    // Never "open" before the list itself is ready: that gate
                    // still belongs to PlaybackState.chaptersReady.
                    if (settled && s.chaptersReady) { emit(true); return@flow }
                    delay(FRAMES_POLL_MS)
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

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

    /** True while the file on screen is a phone video, which has nothing to download. Follows autoplay like [transfer]. */
    val onPhone: StateFlow<Boolean> = state.map { it.fileId }.distinctUntilChanged()
        .map { id -> id != null && id != RegolithKey.Player.EXTERNAL && phone.isPhoneFile(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

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

    /**
     * What the open mark is currently called, or null when no row is open.
     * Its own flow so the suggestion query below only re-runs when the TEXT
     * changes — dragging a handle rewrites the draft many times a second.
     */
    private val openMarkTitle: Flow<String?> = _chapterDraft
        .map { draft -> draft?.selected?.let { draft.marks.getOrNull(it)?.title.orEmpty() } }
        .distinctUntilChanged()

    /**
     * Names already used elsewhere in the library, offered as chips under
     * the name field (P13).
     *
     * The library ends up with "The heist", "the heist" and "Heist" as three
     * separate moments in Search because nothing ever showed you the name
     * you used last time. These do: they come from `user_chapter_fts`, the
     * same index Search reads, and tapping one writes it verbatim, so the
     * spelling stays put.
     *
     * An empty field gets the library's commonest names rather than nothing
     * — that is when the prompt is worth the most. The ONLY name dropped is
     * the one already in the box, because tapping that chip would do
     * nothing. Names this film already uses on another mark stay: the
     * owner's call, and right — a film can have two "Opening credits", and
     * a list that hides what you have used is a list you cannot trust.
     */
    val chapterNameSuggestions: StateFlow<List<String>> = combine(
        openMarkTitle.debounce(SUGGEST_DEBOUNCE_MS).flatMapLatest { typed ->
            if (typed == null) flowOf(emptyList()) else userChapters.nameSuggestions(typed, SUGGEST_QUERY_LIMIT)
        },
        _chapterDraft,
    ) { found, draft ->
        val open = draft?.selected
        if (open == null) {
            emptyList()
        } else {
            val typed = draft.marks.getOrNull(open)?.title?.trim().orEmpty()
            found.map { it.title }.filter { !it.equals(typed, ignoreCase = true) }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // Opening a mark, or moving one, takes the film to it. Marking at the
    // playhead does not: the film is already there.
    fun markChapterAtPlayhead() = editDraft { it.mark(state.value.positionMs) }
    fun selectMark(index: Int?) = editDraft { it.select(index) }.also { seekToOpenMark(throttle = false) }
    fun moveMark(index: Int, ms: Long) = editDraft { it.move(index, ms) }.also { seekToOpenMark(throttle = true) }
    fun nudgeMark(index: Int, deltaMs: Long) = editDraft { it.nudge(index, deltaMs) }.also { seekToOpenMark(throttle = false) }
    fun renameMark(index: Int, title: String) = editDraft { it.rename(index, title) }
    fun removeMark(index: Int) = editDraft { it.remove(index) }
    /** "Remove all chapters": back to a single unnamed start mark. Cancel still undoes it. */
    fun clearAllMarks() = editDraft { it.clearAll() }

    /** True while Save is writing to the share; the button shows a ring. */
    val chapterSaving: StateFlow<Boolean> get() = _chapterSaving
    private val _chapterSaving = MutableStateFlow(false)

    /**
     * True when the film on screen can be given a poster: it is in the
     * library and lives on a share (a video on the phone has no folder
     * Regolith can write to). Hides the Playback sheet's
     * "Make a poster" row otherwise.
     */
    val canMakePoster: StateFlow<Boolean> = state.map { it.fileId }.distinctUntilChanged()
        .mapLatest { id -> id != null && id != RegolithKey.Player.EXTERNAL && posters.target(id) != null }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** "Poster saved to …", once the editor has closed and this screen is back. */
    val posterMessages: Flow<String> get() = posters.saved

    /**
     * Pause and say where: the poster editor opens on the frame the film
     * was showing. Paused, not stopped, so coming back picks up there.
     */
    fun pauseForPoster(): Long {
        val s = state.value
        if (s.isPlaying) session.togglePlayPause()
        return s.positionMs
    }

    /** One line per Save, for the snackbar: where the chapters ended up. */
    val chapterSaveMessages: SharedFlow<String> get() = _chapterSaveMessages
    private val _chapterSaveMessages = MutableSharedFlow<String>(extraBufferCapacity = 1)

    /**
     * Save: the rows first (the scrubber updates at once), then the file
     * on the share, waited for, so the button can say how it went. The
     * editor closes once the answer is in.
     */
    fun saveChapters() {
        val draft = _chapterDraft.value ?: return
        if (_chapterSaving.value) return
        _chapterSaving.value = true
        viewModelScope.launch {
            userChapters.save(draft.fileId, draft.chapters)
            val outcome = runCatching { userChapters.writeNow(draft.fileId) }.getOrDefault(ChapterWriteOutcome.FAILED)
            _chapterDraft.value = null
            _chapterSaving.value = false
            _chapterSaveMessages.tryEmit(
                when (outcome) {
                    ChapterWriteOutcome.WRITTEN -> "Saved to the share"
                    ChapterWriteOutcome.READ_ONLY -> "Saved on this phone — the share is read-only"
                    ChapterWriteOutcome.UNREACHABLE -> "Saved on this phone — the share is out of reach, so it will be written later"
                    ChapterWriteOutcome.PHONE_ONLY -> "Saved on this phone"
                    ChapterWriteOutcome.FAILED -> "Saved on this phone — the share refused the file; it will be tried again"
                },
            )
        }
    }

    fun discardChapterEdit() {
        _chapterDraft.value = null
    }

    /** The one-time sync line on the Chapters sheet has been seen. */
    fun clearChapterNote() {
        val id = state.value.fileId?.takeIf { it != RegolithKey.Player.EXTERNAL } ?: return
        viewModelScope.launch { userChapters.clearNote(id) }
    }

    /** Delete the user's chapters for this film — and the chapter file on the share; the file's own markers or the even split come back. */
    fun revertChapters() {
        val id = state.value.fileId?.takeIf { it != RegolithKey.Player.EXTERNAL } ?: return
        viewModelScope.launch { userChapters.clear(id) }
    }

    private inline fun editDraft(edit: (ChapterDraft) -> ChapterDraft) {
        _chapterDraft.value = _chapterDraft.value?.let(edit)
    }

    private var markSeekJob: Job? = null
    private var lastMarkSeekAtMs = 0L

    /**
     * Put the film on the mark that is open, so the frame on screen is the
     * one being marked (P13). Nothing to do when no row is open — closing a
     * row leaves the film where the last move put it.
     *
     * [throttle] is for a handle being DRAGGED, which calls this many times
     * a second: the first move seeks at once, the rest at most one per
     * [MARK_SEEK_INTERVAL_MS], and the trailing job re-reads the draft when
     * it fires, so wherever the finger stops is where the film ends up. A
     * seek per pointer event would have the player re-reading the share on
     * every pixel. Discrete moves — a tap, a nudge, a typed time — pass
     * false and land immediately.
     */
    private fun seekToOpenMark(throttle: Boolean) {
        if (openMarkMs() == null) return
        markSeekJob?.cancel()
        val wait = if (!throttle) 0L else (MARK_SEEK_INTERVAL_MS - (SystemClock.elapsedRealtime() - lastMarkSeekAtMs)).coerceAtLeast(0L)
        markSeekJob = viewModelScope.launch {
            if (wait > 0) delay(wait)
            val ms = openMarkMs() ?: return@launch
            lastMarkSeekAtMs = SystemClock.elapsedRealtime()
            session.seekTo(ms)
        }
    }

    /** Where the open mark sits, or null when the editor is closed or no row is open. */
    private fun openMarkMs(): Long? = _chapterDraft.value?.let { d -> d.selected?.let { d.marks.getOrNull(it)?.startMs } }

    init {
        // The sheet's pictures are asked for as soon as the chapter list
        // settles, NOT when the sheet opens. They cost a key-frame seek each
        // and the worker serves them one at a time, so starting at the tap
        // meant watching them fill in. Started here, the seeks happen while
        // the film is playing and the sheet is usually full before it opens.
        viewModelScope.launch {
            state.map { it.fileId to it.chaptersReady }.distinctUntilChanged().collect { (_, ready) ->
                if (ready) requestChapterFrames()
            }
        }
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

        /** The most often a handle being dragged may move the film. Four seeks a second is plenty to follow by eye. */
        const val MARK_SEEK_INTERVAL_MS = 250L

        /** A pause in typing before the suggestions re-query: short, it is a local table. */
        const val SUGGEST_DEBOUNCE_MS = 120L

        /**
         * How many names the chips can offer. The row scrolls, and the point
         * is to show what the share HAS — so this is a safety rail against a
         * pathological library, not an editorial cut. Same number as the
         * moment filter's, for the same reason.
         */
        const val SUGGEST_QUERY_LIMIT = 50

        /** The middle of each of [STRIP_FRAMES] equal slices, or empty until the duration is known. */
        fun stripPositions(durationMs: Long): List<Long> =
            if (durationMs <= 0) emptyList() else List(STRIP_FRAMES) { i -> (durationMs * (2 * i + 1)) / (2 * STRIP_FRAMES) }
    }
}

/** One frame of the flex-mode filmstrip: where it is in the film, and the picture once it has arrived. */
data class StripFrame(val positionMs: Long, val bitmap: Bitmap?)
