package com.regolith.player

import android.content.Context
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.regolith.data.artwork.FrameSourceFactory
import com.regolith.data.db.MediaFileEntity
import com.regolith.data.prefs.AppPreferences
import com.regolith.data.repository.LibraryRepository
import com.regolith.data.repository.PlaybackRepository
import com.regolith.data.repository.UserChapterRepository
import com.regolith.data.media.ChapterRepository
import com.regolith.domain.playback.AbLoop
import com.regolith.domain.playback.Chapter
import com.regolith.domain.playback.ChapterMarks
import com.regolith.domain.playback.ChapterSource
import com.regolith.domain.playback.ChapterSyncNote
import com.regolith.domain.playback.ChapterSyncState
import com.regolith.domain.playback.RepeatMode
import com.regolith.domain.playback.VideoInfo
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** One entry of "Next in this folder". */
data class NextItem(val fileId: Long, val name: String, val sizeBytes: Long, val durationMs: Long?)

/** What the Player screen draws. */
data class PlaybackState(
    val fileId: Long? = null,
    val title: String = "",
    /** "TOWER · media/Films" */
    val sourceLabel: String = "",
    val fileSizeBytes: Long = 0,
    /** The player is actually advancing. False while buffering. */
    val isPlaying: Boolean = false,
    /** The user wants playback (play pressed, not paused). What the play/pause button reflects. */
    val playWhenReady: Boolean = false,
    val isBuffering: Boolean = false,
    /** Reached the end; the button offers a replay. */
    val ended: Boolean = false,
    val positionMs: Long = 0,
    val bufferedMs: Long = 0,
    val durationMs: Long = 0,
    val speed: Float = 1f,
    /** 2× while the user holds a long-press; [speed] is restored on release. */
    val holdingFast: Boolean = false,
    val hardwareDecoding: Boolean = true,
    val video: VideoInfo? = null,
    /** Set A, waiting for B. */
    val loopPendingAMs: Long? = null,
    val loop: AbLoop? = null,
    val next: List<NextItem> = emptyList(),
    /** What Previous plays: the file before this one in the queue, or in the folder. */
    val previous: NextItem? = null,
    /**
     * True while an explicit queue is playing (Play all / Shuffle). [next] is
     * then the rest of that queue rather than the rest of the folder, and the
     * player plays on whether or not "Keep playing" is switched on: you asked
     * for all of them.
     */
    val queued: Boolean = false,
    /**
     * The running order is scrambled. True only for an order this player
     * made: [com.regolith.ui.navigation.RegolithNavGraph]'s Shuffle button
     * hands over a queue that is already shuffled and cannot be un-shuffled,
     * because the order it came from is gone by then.
     */
    val shuffled: Boolean = false,
    /** What the repeat button is set to. */
    val repeat: RepeatMode = RepeatMode.OFF,
    /** The first file of the running order, for [upNext] to wrap round to. Null when this IS it. */
    val wrapTo: NextItem? = null,
    /** The last file of the running order, for [upPrevious] to wrap back to. Null when this IS it. */
    val wrapToLast: NextItem? = null,
    /**
     * The markers the CONTAINER carries, if any. Read [chapters] instead —
     * this is the raw half of it.
     */
    val containerChapters: List<Chapter> = emptyList(),
    /**
     * The chapters the user wrote for this file, observed from
     * `user_chapters` for as long as it is loaded. Read [chapters] instead.
     */
    val userChapters: List<Chapter> = emptyList(),
    /** Where [userChapters] stand against the sidecar on the share (P10); null when there are none. */
    val chapterSync: ChapterSyncState? = null,
    /** A one-time line for the sheet, e.g. the share's newer file replaced this phone's rows. */
    val chapterSyncNote: ChapterSyncNote? = null,
    /**
     * False until the container has actually been read. Without it there is
     * no way to tell "this file has no chapters" from "we have not looked
     * yet", and the sheet would open on even divisions and then reshuffle
     * itself when the real ones arrived.
     */
    val chaptersScanned: Boolean = false,
    val error: String? = null,
) {
    /**
     * Where you can jump to. The container's own markers when it has them,
     * otherwise the runtime cut into even parts — so every film has them,
     * and a file that was muxed without chapters is no worse off.
     *
     * Derived rather than stored: the even divisions depend on [durationMs],
     * which arrives from the player a moment after the file does.
     */
    val chapters: List<Chapter>
        get() = userChapters.ifEmpty { containerChapters.ifEmpty { ChapterMarks.evenly(durationMs) } }

    /** True when a person named these; false when they are even divisions. */
    val chapterSource: ChapterSource
        get() = when {
            userChapters.isNotEmpty() -> ChapterSource.USER
            containerChapters.isNotEmpty() -> ChapterSource.CONTAINER
            else -> ChapterSource.EVEN
        }

    /**
     * Settled: the container has been read AND the runtime is known, so the
     * list will not change shape under the sheet. The pill spins until this
     * is true rather than opening onto a list that then resizes itself.
     */
    val chaptersReady: Boolean get() = chaptersScanned && durationMs > 0 && chapters.isNotEmpty()

    /** Just the starts, for the ticks [com.regolith.ui.components.Scrubber] draws. */
    /** The chapter the playhead is inside, or null when the file has none. */
    fun chapterAt(ms: Long): Chapter? = chapters.lastOrNull { it.startMs <= ms }

    /** What the scrub preview says: the chapter's name, or "Part n" for an unnamed one; null when the file has none. */
    fun chapterLabelAt(ms: Long): String? {
        val index = chapters.indexOfLast { it.startMs <= ms }
        return if (index < 0) null else chapters[index].label(index)
    }

    /**
     * What plays after this one — what Next does, and what autoplay reaches
     * for. Normally the next file in the running order; at the end of that
     * order it is the first file again, but only while repeating all.
     *
     * [RepeatMode.ONE] never appears here: the player loops the file itself
     * and the end is never reached.
     */
    val upNext: NextItem? get() = next.firstOrNull() ?: wrapTo.takeIf { repeat == RepeatMode.ALL }

    /** The mirror of [upNext]: Previous wraps back to the last file while repeating all. */
    val upPrevious: NextItem? get() = previous ?: wrapToLast.takeIf { repeat == RepeatMode.ALL }
}

/**
 * The one ExoPlayer, owned by the app rather than by a screen (guardrail
 * G4). The Player screen observes [state] and sends commands; if it is
 * rotated, recreated or left, playback and progress saving carry on here.
 *
 * The player instance can change: switching hardware/software decoding
 * needs a new renderers factory, which means a new ExoPlayer. That is why
 * [player] is a StateFlow rather than a plain property.
 *
 * ExoPlayer is main-thread only, so every method here is called from the
 * UI and the ticker runs on `Dispatchers.Main`.
 */
@UnstableApi
@Singleton
class PlaybackSession @Inject constructor(
    @ApplicationContext private val context: Context,
    private val smbDataSourceFactory: SmbDataSource.Factory,
    private val resolver: MediaUriResolver,
    private val library: LibraryRepository,
    private val playback: PlaybackRepository,
    private val prefs: AppPreferences,
    private val frames: FrameSourceFactory,
    private val local: LocalMedia,
    private val chapterSource: ChapterRepository,
    private val userChapters: UserChapterRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _state = MutableStateFlow(PlaybackState())
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private val _player = MutableStateFlow<ExoPlayer?>(null)
    /** The current player for the video surface; null until first use. */
    val player: StateFlow<ExoPlayer?> = _player.asStateFlow()

    private val _scrubThumbnails = MutableStateFlow<ScrubThumbnails>(ScrubThumbnails.None)
    /** Preview frames for the loaded file; [ScrubThumbnails.None] when off or nothing is loaded. */
    val scrubThumbnails: StateFlow<ScrubThumbnails> = _scrubThumbnails.asStateFlow()

    private var ticker: Job? = null
    /** Follows `user_chapters` for the loaded file, so a save in the editor lands on the scrubber at once. */
    private var userChapterJob: Job? = null
    private var lastSavedPositionMs = -1L
    private var currentFile: MediaFileEntity? = null
    /** file:// for a copy on this device, regolith:// for the share. */
    private var currentUri: android.net.Uri? = null

    /**
     * The running order set by Play all or Shuffle: file ids in the order you
     * saw them on the wall. Empty means no queue, and "next" falls back to
     * the rest of the folder. It lives here rather than in a ViewModel
     * because the session is what outlives the player screen (G4), and it is
     * dropped the moment a file outside it is opened.
     */
    private var activeQueue: List<Long> = emptyList()

    /**
     * True while [activeQueue] is an order THIS player scrambled, so the
     * shuffle button can put it back. A queue handed over by Play all ›
     * Shuffle is already scrambled and cannot be put back — the order it was
     * made from is gone by the time the player sees it.
     */
    private var shuffledHere: Boolean = false

    private fun current(): ExoPlayer = _player.value ?: createPlayer(_state.value.hardwareDecoding).also { _player.value = it }

    private fun createPlayer(hardware: Boolean): ExoPlayer {
        // DefaultDataSource handles file:// (Phase 5 downloads) itself and
        // hands every other scheme, i.e. regolith://, to our SMB factory.
        val dataSourceFactory = DefaultDataSource.Factory(context, smbDataSourceFactory)
        val renderers = DefaultRenderersFactory(context)
            .setEnableDecoderFallback(true)
            .setMediaCodecSelector(if (hardware) MediaCodecSelector.DEFAULT else MediaCodecSelector.PREFER_SOFTWARE)
        return ExoPlayer.Builder(context, renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(context).setDataSourceFactory(dataSourceFactory))
            .build()
            .also {
                it.addListener(listener)
                it.setPlaybackSpeed(_state.value.speed)
                it.repeatMode = if (_state.value.repeat == RepeatMode.ONE) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
            }
    }

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            Log.d(TAG, "isPlaying=$isPlaying pos=${_player.value?.currentPosition}")
            _state.update { it.copy(isPlaying = isPlaying) }
            if (isPlaying) startTicker() else saveProgress()
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            Log.d(TAG, "playWhenReady=$playWhenReady reason=$reason pos=${_player.value?.currentPosition}")
            _state.update { it.copy(playWhenReady = playWhenReady) }
        }

        override fun onPlaybackSuppressionReasonChanged(playbackSuppressionReason: Int) {
            Log.d(TAG, "suppression=$playbackSuppressionReason")
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            Log.d(TAG, "playbackState=$playbackState pos=${_player.value?.currentPosition}")
            _state.update {
                it.copy(
                    isBuffering = playbackState == Player.STATE_BUFFERING,
                    ended = playbackState == Player.STATE_ENDED,
                    durationMs = current().duration.coerceAtLeast(0),
                )
            }
            if (playbackState == Player.STATE_ENDED) saveProgress()
        }

        override fun onTracksChanged(tracks: Tracks) {
            val video = tracks.groups.firstOrNull { it.type == C.TRACK_TYPE_VIDEO && it.isSelected }?.let { g -> selectedFormat(g) }
            val audio = tracks.groups.firstOrNull { it.type == C.TRACK_TYPE_AUDIO && it.isSelected }?.let { g -> selectedFormat(g) }
            if (video != null) {
                val transfer = video.colorInfo?.colorTransfer
                _state.update {
                    it.copy(
                        video = VideoInfo(
                            width = video.width,
                            height = video.height,
                            videoMimeType = video.sampleMimeType,
                            hdr = transfer == C.COLOR_TRANSFER_ST2084 || transfer == C.COLOR_TRANSFER_HLG,
                            audioMimeType = audio?.sampleMimeType,
                            audioChannels = audio?.channelCount?.takeIf { c -> c > 0 },
                        ),
                    )
                }
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            _state.update { it.copy(error = error.message ?: error.errorCodeName, isPlaying = false) }
        }
    }

    private fun selectedFormat(group: Tracks.Group): Format? =
        (0 until group.length).firstOrNull { group.isTrackSelected(it) }?.let { group.getTrackFormat(it) }

    /**
     * Load and play a file. Resumes from the saved position unless
     * [startMs] says otherwise. Safe to call for the file already loaded.
     */
    /**
     * Play [fileId]. [queue] is an explicit running order (Play all, Shuffle);
     * pass null to keep whatever queue is running — which is what the
     * autoplay step does, so walking a queue does not destroy it — and the
     * queue is dropped as soon as a file outside it is opened.
     */
    fun load(fileId: Long, startMs: Long? = null, queue: List<Long>? = null) {
        Log.d(TAG, "load($fileId, $startMs) current=${_state.value.fileId} queue=${queue?.size ?: activeQueue.size}")
        when {
            // An order handed over by Play all / Shuffle. It may already be
            // scrambled, but not by us, so the button reads off: there is no
            // original order left to put it back to.
            queue != null -> { activeQueue = queue; shuffledHere = false }
            // A file from outside the queue ends the queue, and with it the shuffle.
            !activeQueue.contains(fileId) -> { activeQueue = emptyList(); shuffledHere = false }
        }
        if (_state.value.fileId == fileId && _state.value.error == null) {
            if (!current().isPlaying) current().play()
            return
        }
        saveProgress()
        _state.value = PlaybackState(fileId = fileId, speed = _state.value.speed, hardwareDecoding = _state.value.hardwareDecoding)
        followUserChapters(fileId)
        scope.launch {
            val hardware = prefs.hardwareDecoding.first()
            if (hardware != _state.value.hardwareDecoding) {
                _state.update { it.copy(hardwareDecoding = hardware) }
                _player.value?.release()
                _player.value = null
            }
            val file = library.file(fileId)
            currentFile = file
            val resume = startMs ?: playback.progress(fileId)?.takeUnless { it.completed }?.positionMs ?: 0L
            val view = queueView(fileId)
            val repeat = prefs.playerRepeat.first()
            val uri = resolver.playableUriFor(fileId)
            _state.update {
                it.copy(
                    title = file?.name?.substringBeforeLast('.') ?: "",
                    sourceLabel = if (resolver.isLocal(uri)) "On this device" else file?.let { f -> sourceLabelFor(f) } ?: "",
                    fileSizeBytes = file?.sizeBytes ?: 0,
                    next = view.next,
                    previous = view.previous,
                    wrapTo = view.wrapTo,
                    wrapToLast = view.wrapToLast,
                    queued = activeQueue.isNotEmpty(),
                    shuffled = shuffledHere,
                    repeat = repeat,
                )
            }
            currentUri = uri
            startPlayer(fileId, file, resume)
            // After the player is started: the header read is worth a second
            // of latency on a sheet nobody has opened yet, and never worth
            // delaying the picture.
            val marks = chapterSource.chapters(fileId)
            if (_state.value.fileId == fileId) {
                _state.update { it.copy(containerChapters = marks, chaptersScanned = true) }
            }
            setScrubThumbnails(prefs.scrubThumbnails.first())
        }
    }

    /**
     * Play a film another app handed us: a `content://` or `file://` URI and
     * whatever name came with it.
     *
     * Everything the library provides is simply absent — no row, so no
     * artwork, no folder, no "next in this folder", no resume point, and no
     * scrub previews (those need a seekable handle on the file, which the
     * library resolves and a foreign URI does not). What survives is the
     * player itself: transport, speed, decoder, A–B, and chapters, which are
     * even divisions of a runtime and need nothing but the runtime.
     */
    fun loadExternal(uri: android.net.Uri, title: String) {
        Log.d(TAG, "loadExternal($uri)")
        saveProgress()
        userChapterJob?.cancel()
        activeQueue = emptyList()
        currentFile = null
        currentUri = uri
        _state.value = PlaybackState(
            fileId = null,
            title = title,
            sourceLabel = "Opened from another app",
            speed = _state.value.speed,
            hardwareDecoding = _state.value.hardwareDecoding,
            // Nothing to read a container for, and nothing to wait on: the
            // even divisions arrive with the runtime.
            chaptersScanned = true,
        )
        _scrubThumbnails.value.close()
        _scrubThumbnails.value = ScrubThumbnails.None
        scope.launch {
            val hardware = prefs.hardwareDecoding.first()
            if (hardware != _state.value.hardwareDecoding) {
                _state.update { it.copy(hardwareDecoding = hardware) }
                _player.value?.release()
                _player.value = null
            }
            startPlayer(null, null, 0L)
        }
    }

    /**
     * Turn preview frames on or off for the loaded file. On means a second
     * read handle on the share that is only used while the user drags.
     */
    fun setScrubThumbnails(enabled: Boolean) {
        scope.launch { prefs.setScrubThumbnails(enabled) }
        _scrubThumbnails.value.close()
        val fileId = _state.value.fileId
        val file = currentFile
        _scrubThumbnails.value = if (enabled && fileId != null && file != null) {
            OnDemandScrubThumbnails(durationMs = file.durationMs ?: _state.value.durationMs) {
                // Same rule as playback: the copy on this device first.
                local.fileBlocking(fileId)?.let { return@OnDemandScrubThumbnails frames.openLocal(it) }
                val media = resolver.resolveBlocking(fileId) ?: error("file $fileId is unknown")
                frames.open(media.host, media.credentials, media.share, media.relPath)
            }
        } else {
            ScrubThumbnails.None
        }
    }

    /** What the transport needs to know about the running order this file sits in. */
    private data class QueueView(
        val next: List<NextItem>,
        val previous: NextItem?,
        val wrapTo: NextItem?,
        val wrapToLast: NextItem?,
    )

    /**
     * Where [fileId] sits in what is playing: an explicit queue when one is
     * running, otherwise the folder in name order — the order Browse shows.
     *
     * Also the two ends of that order, which is all repeat-all needs: the
     * first file to follow the last, and the last to precede the first.
     */
    private suspend fun queueView(fileId: Long): QueueView {
        val queued = activeQueue.isNotEmpty()
        val here = activeQueue.indexOf(fileId)
        val next = (if (queued) library.filesInOrder(activeQueue.drop(here + 1)) else library.filesAfter(fileId))
            .map { it.toNextItem() }
        // Null at the first file, which is what greys the button out.
        val previousId = if (queued) activeQueue.getOrNull(here - 1) else library.fileBefore(fileId)?.id
        val order = if (queued) activeQueue else library.file(fileId)?.let { f -> library.filesInFolder(f.folderId).map { it.id } }.orEmpty()
        return QueueView(
            next = next,
            previous = previousId?.let { library.file(it)?.toNextItem() },
            wrapTo = order.firstOrNull()?.takeIf { it != fileId }?.let { library.file(it)?.toNextItem() },
            wrapToLast = order.lastOrNull()?.takeIf { it != fileId }?.let { library.file(it)?.toNextItem() },
        )
    }

    private fun MediaFileEntity.toNextItem() = NextItem(id, name.substringBeforeLast('.'), sizeBytes, durationMs)

    /**
     * The repeat button, cycled from the player. [RepeatMode.ONE] is handed
     * to ExoPlayer, which loops the file without ever reaching its end — so
     * autoplay never sees an ending and nothing else has to know. The other
     * two are ours: they only change what [PlaybackState.upNext] answers.
     */
    fun setRepeat(mode: RepeatMode) {
        _state.update { it.copy(repeat = mode) }
        applyRepeat(mode)
        scope.launch { prefs.setPlayerRepeat(mode) }
    }

    private fun applyRepeat(mode: RepeatMode) {
        _player.value?.repeatMode = if (mode == RepeatMode.ONE) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
    }

    /**
     * Scramble what is left to play, or put it back.
     *
     * On: the running order becomes this file followed by everything else in
     * a random order, so what you are watching is not interrupted. Off: the
     * queue is dropped and "next" goes back to the folder in name order.
     *
     * A queue that arrived already shuffled (Play all › Shuffle) can be
     * turned off the same way — it drops back to the folder — but the order
     * it came from is gone, so it cannot be restored exactly.
     */
    fun setShuffle(on: Boolean) {
        val fileId = _state.value.fileId ?: return
        scope.launch {
            if (on) {
                val order = if (activeQueue.isNotEmpty()) {
                    activeQueue
                } else {
                    library.file(fileId)?.let { f -> library.filesInFolder(f.folderId).map { it.id } }.orEmpty()
                }
                activeQueue = listOf(fileId) + (order - fileId).shuffled()
                shuffledHere = true
            } else {
                activeQueue = emptyList()
                shuffledHere = false
            }
            val view = queueView(fileId)
            _state.update {
                it.copy(
                    next = view.next, previous = view.previous,
                    wrapTo = view.wrapTo, wrapToLast = view.wrapToLast,
                    queued = activeQueue.isNotEmpty(), shuffled = shuffledHere,
                )
            }
        }
    }

    private fun startPlayer(fileId: Long?, file: MediaFileEntity?, positionMs: Long) {
        val item = MediaItem.Builder()
            .setUri(currentUri ?: resolver.uriFor(checkNotNull(fileId) { "no file and no uri" }))
            .setMediaId(fileId?.toString() ?: EXTERNAL_MEDIA_ID)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(file?.name ?: _state.value.title).build())
            .build()
        val p = current()
        p.repeatMode = if (_state.value.repeat == RepeatMode.ONE) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        p.setMediaItem(item, positionMs)
        p.prepare()
        p.play()
    }

    private suspend fun sourceLabelFor(file: MediaFileEntity): String {
        val folder = file.relPath.substringBeforeLast('/', "")
        val share = library.shareLabel(file.shareId)
        return listOf(share, folder).filter { it.isNotEmpty() }.joinToString(" · ")
    }

    // --- transport

    fun togglePlayPause() {
        val p = current()
        // Decide on intent, not on isPlaying: during a rebuffer isPlaying is
        // false but the user has not paused, and a tap then must pause.
        when {
            p.playbackState == Player.STATE_ENDED -> { p.seekTo(0); p.play() }
            p.playWhenReady -> p.pause()
            else -> p.play()
        }
    }

    /**
     * Pause without asking what the user meant. The app lock uses this:
     * a film whose sound carries on behind the lock screen is a film
     * playing to whoever picked the phone up.
     */
    fun pause() {
        if (_player.value?.playWhenReady == true) current().pause()
    }

    fun seekTo(positionMs: Long) {
        val p = current()
        p.seekTo(positionMs.coerceIn(0, p.duration.coerceAtLeast(0)))
        _state.update { it.copy(positionMs = p.currentPosition) }
    }

    fun seekBy(deltaMs: Long) = seekTo(current().currentPosition + deltaMs)

    fun setSpeed(speed: Float) {
        _state.update { it.copy(speed = speed) }
        if (!_state.value.holdingFast) current().setPlaybackSpeed(speed)
    }

    /** Long-press: 2× while held. */
    fun holdFast(hold: Boolean) {
        _state.update { it.copy(holdingFast = hold) }
        current().setPlaybackSpeed(if (hold) HOLD_SPEED else _state.value.speed)
    }

    /**
     * Hardware or software decoding. The choice is remembered as the
     * default and takes effect now by rebuilding the player at the same
     * position; the surface follows via [player].
     */
    fun setHardwareDecoding(hardware: Boolean) {
        if (hardware == _state.value.hardwareDecoding) return
        scope.launch { prefs.setHardwareDecoding(hardware) }
        val old = _player.value
        val position = old?.currentPosition ?: _state.value.positionMs
        val fileId = _state.value.fileId
        ticker?.cancel()
        old?.release()
        _player.value = null
        _state.update { it.copy(hardwareDecoding = hardware, isPlaying = false) }
        if (fileId != null) startPlayer(fileId, currentFile, position)
    }

    // --- A–B loop

    /** The A–B pill: first tap sets A, second sets B and arms the loop. */
    fun tapLoopPoint() {
        val now = current().currentPosition
        Log.d(TAG, "tapLoopPoint at $now pendingA=${_state.value.loopPendingAMs} loop=${_state.value.loop}")
        val s = _state.value
        when {
            s.loop != null -> Unit // sheet handles an armed loop
            s.loopPendingAMs == null -> _state.update { it.copy(loopPendingAMs = now) }
            else -> _state.update { it.copy(loopPendingAMs = null, loop = AbLoop.between(s.loopPendingAMs, now, it.durationMs)) }
        }
    }

    fun nudgeLoopA(deltaMs: Long) = _state.update { s -> s.copy(loop = s.loop?.nudgeA(deltaMs)) }
    fun nudgeLoopB(deltaMs: Long) = _state.update { s -> s.copy(loop = s.loop?.nudgeB(deltaMs, s.durationMs)) }
    fun clearLoop() = _state.update { it.copy(loop = null, loopPendingAMs = null) }

    // --- progress

    /** Persist the current position now (screen leaving, app backgrounded). */
    fun saveProgress() {
        val fileId = _state.value.fileId ?: return
        val p = _player.value ?: return
        val position = p.currentPosition
        val duration = p.duration
        if (duration <= 0 || position == lastSavedPositionMs) return
        lastSavedPositionMs = position
        scope.launch(Dispatchers.IO) { playback.save(fileId, position, duration) }
    }

    /** Stop and free the decoder. Next [load] creates a fresh player. */
    fun stop() {
        Log.d(TAG, "stop() file=${_state.value.fileId}")
        saveProgress()
        ticker?.cancel()
        userChapterJob?.cancel()
        _player.value?.let {
            it.stop()
            it.clearMediaItems()
            it.release()
        }
        _player.value = null
        currentFile = null
        currentUri = null
        _scrubThumbnails.value.close()
        _scrubThumbnails.value = ScrubThumbnails.None
        _state.value = PlaybackState(speed = 1f, hardwareDecoding = _state.value.hardwareDecoding)
    }

    /**
     * Keep [PlaybackState.userChapters] in step with the table while
     * [fileId] is loaded. One collector per load; the guard drops a late
     * emission for a file that has since been swapped out.
     */
    private fun followUserChapters(fileId: Long) {
        userChapterJob?.cancel()
        userChapterJob = scope.launch {
            combine(userChapters.observe(fileId), userChapters.observeSync(fileId)) { marks, sync -> marks to sync }.collect { (marks, sync) ->
                if (_state.value.fileId == fileId) _state.update { it.copy(userChapters = marks, chapterSync = sync.state, chapterSyncNote = sync.note) }
            }
        }
    }

    private fun startTicker() {
        ticker?.cancel()
        ticker = scope.launch {
            var sinceSave = 0
            while (isActive && _player.value?.isPlaying == true) {
                val p = current()
                val position = p.currentPosition
                _state.value.loop?.let { loop ->
                    if (loop.shouldRestart(position)) {
                        Log.d(TAG, "loop restart at $position -> ${loop.aMs}")
                        p.seekTo(loop.aMs)
                    }
                }
                _state.update {
                    it.copy(positionMs = p.currentPosition, bufferedMs = p.bufferedPosition, durationMs = p.duration.coerceAtLeast(0))
                }
                if (++sinceSave >= SAVE_EVERY_TICKS) {
                    sinceSave = 0
                    saveProgress()
                    Log.d(TAG, "tick pos=${p.currentPosition} buffered=${p.bufferedPosition} state=${p.playbackState} loading=${p.isLoading}")
                }
                delay(TICK_MS)
            }
        }
    }

    private companion object {
        const val EXTERNAL_MEDIA_ID = "external"
        const val TAG = "Regolith/Playback"
        const val TICK_MS = 250L
        const val SAVE_EVERY_TICKS = 20 // every 5 s while playing
        const val HOLD_SPEED = 2f
    }
}
