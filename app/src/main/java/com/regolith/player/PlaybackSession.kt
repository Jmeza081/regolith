package com.regolith.player

import com.regolith.data.spoof.spoofed
import com.regolith.data.spoof.SpoofMode
import android.content.Context
import android.util.Log
import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.AudioAttributes
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
import androidx.media3.session.MediaSession
import com.regolith.data.artwork.ArtworkStore
import com.regolith.domain.artwork.ArtworkKind
import com.regolith.domain.artwork.ArtworkOwner
import com.regolith.data.artwork.FrameGrabber
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
import com.regolith.domain.playback.ReelClip
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** One entry of "Next in this folder". */
data class NextItem(val fileId: Long, val name: String, val sizeBytes: Long, val durationMs: Long?)

/**
 * A Moments reel as it plays ([PlaybackSession.loadReel]): its name (the
 * collection's), its clips in the order they play, the one playing, and
 * whether what is left is shuffled ([original] is the order to put it back in).
 */
data class ReelState(
    val title: String,
    val clips: List<ReelClip>,
    val index: Int,
    val shuffled: Boolean = false,
    val original: List<ReelClip> = clips,
) {
    val clip: ReelClip get() = clips[index]
    val hasPrevious: Boolean get() = index > 0
    val hasNext: Boolean get() = index < clips.lastIndex
}

/** What the Player screen draws. */
data class PlaybackState(
    /**
     * Something is loaded, a library file or a film another app handed over
     * ([fileId] is null for that one): what keeps the mini player on screen
     * once the player itself has been put away. False after [PlaybackSession.stop].
     */
    val loaded: Boolean = false,
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
    /** A Moments reel playing instead of one film ([PlaybackSession.loadReel]); null the rest of the time. */
    val reel: ReelState? = null,
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

    /**
     * What the player goes on to by itself when this film ends, or null when
     * it stops there. A running order (Play all, Shuffle) and repeat-all both
     * mean "keep going" whatever the setting says; otherwise it is Settings ›
     * Playback › Keep playing, [keepPlaying]. The player's Up next card and
     * the mini player both ask this, so they never disagree.
     */
    fun playsOnTo(keepPlaying: Boolean): NextItem? =
        upNext.takeIf { keepPlaying || queued || repeat == RepeatMode.ALL }
}

/**
 * The one ExoPlayer, owned by the app rather than by a screen (guardrail
 * G4). The Player screen observes [state] and sends commands; if it is
 * rotated, recreated or left, playback and progress saving carry on here.
 * The mini player, the picture-in-picture window and the lock screen's
 * controls ([mediaSession]) all show this same player.
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
    /** Media3's extractor, the scrub previews' fallback when the platform one cannot seek. */
    private val frameGrabber: FrameGrabber,
    private val local: LocalMedia,
    private val chapterSource: ChapterRepository,
    private val userChapters: UserChapterRepository,
    private val spoof: SpoofMode,
    /** Where a film's thumb is cached, for the notification's picture. */
    private val artwork: ArtworkStore,
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
    /** The loaded film's thumb, for the notification's picture ([artFor]). */
    private var currentArt: ByteArray? = null

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

    private fun current(): ExoPlayer = _player.value ?: createPlayer(_state.value.hardwareDecoding).also {
        _player.value = it
        publish(it)
    }

    /**
     * The media session over the current player, while there is a film:
     * what [PlaybackService] publishes as the notification, the lock-screen
     * controls and the picture-in-picture window's buttons. Made and released
     * here, beside the player it wraps, so it can never be left holding one
     * that has been released.
     */
    var mediaSession: MediaSession? = null
        private set

    /** What [mediaSession] shows: the current player, with Previous and Next following the running order. */
    private var sessionPlayer: SessionPlayer? = null

    init {
        // The running order changes without the player noticing (a queue
        // worked out after the film opened, repeat switched on), so the lock
        // screen's buttons are told when Previous or Next comes or goes.
        scope.launch {
            _state.map { (it.upPrevious != null) to (it.upNext != null) }.distinctUntilChanged().collect {
                sessionPlayer?.stepsChanged()
            }
        }
    }

    /**
     * True while the player screen is in front of you: it puts up its own Up
     * next card when a film ends and decides what follows. Everywhere else —
     * the mini player, the picture-in-picture window, the screen off — the
     * session decides at the end itself ([atTheEnd]), because nothing on
     * screen is drawing then: Compose stops when its window cannot be seen.
     */
    var screenOwnsTheEnd = false

    /**
     * True while the film is in its picture-in-picture window, which keeps
     * showing a film that has finished instead of vanishing under you; you
     * close the window to end it.
     */
    var floating = false

    /**
     * Player screens open, in front of you or not: normally one, two when a
     * film handed over by another app opened over one. A film that ends under
     * a player you cannot see — the screen off — is held at its end for you
     * to come back to, as if you had watched it finish ([atTheEnd]).
     */
    private var playerScreens = 0

    /** A player screen opened (its ViewModel was made); paired with [playerScreenClosed]. */
    fun playerScreenOpened() {
        playerScreens++
    }

    /** A player screen was put away. */
    fun playerScreenClosed() {
        playerScreens = (playerScreens - 1).coerceAtLeast(0)
    }

    /**
     * With the app lock on, the notification and the lock screen say only
     * that Regolith is playing: a film's name is the owner's own, and the
     * lock is there to keep it to them. Read at each load.
     */
    private var discreet = false

    /** Put [player] behind the media session, making it — and starting its service — the first time. */
    private fun publish(player: ExoPlayer) {
        val shown = SessionPlayer(player, steps = { _state.value }, go = { load(it) })
        sessionPlayer = shown
        mediaSession?.let {
            it.player = shown
            return
        }
        val open = context.packageManager.getLaunchIntentForPackage(context.packageName)
        mediaSession = MediaSession.Builder(context, shown)
            .apply {
                if (open != null) {
                    setSessionActivity(PendingIntent.getActivity(context, 0, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
                }
            }
            .build()
        context.startService(Intent(context, PlaybackService::class.java))
    }

    private fun createPlayer(hardware: Boolean): ExoPlayer {
        surfaceShown = false
        // DefaultDataSource handles file:// (Phase 5 downloads) itself and
        // hands every other scheme, i.e. regolith://, to our SMB factory.
        val dataSourceFactory = DefaultDataSource.Factory(context, smbDataSourceFactory)
        val renderers = DefaultRenderersFactory(context)
            .setEnableDecoderFallback(true)
            .setMediaCodecSelector(if (hardware) MediaCodecSelector.DEFAULT else MediaCodecSelector.PREFER_SOFTWARE)
        return ExoPlayer.Builder(context, renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(context).setDataSourceFactory(dataSourceFactory))
            // Keeps the CPU and the Wi-Fi awake while a film plays, which only
            // matters once the screen is off: without it the share stops being
            // read and the sound stops with it. Held only while playing.
            .setWakeMode(C.WAKE_MODE_NETWORK)
            // A film is a film to the rest of the phone: it pauses for a call,
            // ducks under a notification, and stops when the headphones come out.
            .setAudioAttributes(MOVIE_AUDIO, true)
            .setHandleAudioBecomingNoisy(true)
            .build()
            .also {
                it.addListener(listener)
                it.setPlaybackSpeed(_state.value.speed)
                it.repeatMode = if (_state.value.repeat == RepeatMode.ONE) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
            }
    }

    private val listener = object : Player.Listener {
        // A reel moving on to its next clip: the clip is the film now, as far
        // as anything showing the session is concerned.
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val reel = _state.value.reel ?: return
            val index = _player.value?.currentMediaItemIndex ?: return
            val clip = reel.clips.getOrNull(index) ?: return
            playerFileId = clip.fileId
            _state.update {
                it.copy(
                    fileId = clip.fileId, title = clip.name, sourceLabel = clip.videoName,
                    positionMs = 0, durationMs = clip.durationMs, reel = reel.copy(index = index),
                )
            }
        }

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
            if (playbackState == Player.STATE_ENDED) {
                saveProgress()
                // Ended with nothing showing it (the screen off): the next
                // surface is given the last frame ([redrawIfEnded]).
                if (!surfaceShown) redrawOnNewSurface = true
                scope.launch { atTheEnd() }
            }
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
                            frameRate = video.frameRate.takeIf { r -> r > 0f },
                        ),
                    )
                }
            }
        }

        override fun onSurfaceSizeChanged(width: Int, height: Int) {
            val p = _player.value ?: return
            val ended = p.playbackState == Player.STATE_ENDED
            if (width <= 0 || height <= 0) {
                // The surface went: the screen went off, or another app came
                // over this one. A finished film will want its frame back.
                surfaceShown = false
                if (ended) redrawOnNewSurface = true
                return
            }
            surfaceShown = true
            if (!redrawOnNewSurface) return
            redrawOnNewSurface = false
            if (ended) p.seekTo((p.duration - REDRAW_BEFORE_END_MS).coerceAtLeast(0))
        }

        override fun onPlayerError(error: PlaybackException) {
            _state.update { it.copy(error = error.message ?: error.errorCodeName, isPlaying = false) }
        }
    }

    private fun selectedFormat(group: Tracks.Group): Format? =
        (0 until group.length).firstOrNull { group.isTrackSelected(it) }?.let { group.getTrackFormat(it) }

    /**
     * Load and play [fileId]. Resumes from the saved position unless
     * [startMs] says otherwise. Safe to call for the file already loaded.
     * [queue] is an explicit running order (Play all, Shuffle); pass null to
     * keep whatever queue is running — which is what the autoplay step does,
     * so walking a queue does not destroy it — and the queue is dropped as
     * soon as a file outside it is opened.
     *
     * [startPlaying] false opens the film without starting it, for a player
     * whose picture is still flying in; [play] starts it once it has landed.
     */
    fun load(fileId: Long, startMs: Long? = null, queue: List<Long>? = null, startPlaying: Boolean = true) {
        Log.d(TAG, "load($fileId, $startMs) current=${_state.value.fileId} queue=${queue?.size ?: activeQueue.size}")
        holdStart = !startPlaying
        when {
            // An order handed over by Play all / Shuffle. It may already be
            // scrambled, but not by us, so the button reads off: there is no
            // original order left to put it back to.
            queue != null -> { activeQueue = queue; shuffledHere = false }
            // A file from outside the queue ends the queue, and with it the shuffle.
            !activeQueue.contains(fileId) -> { activeQueue = emptyList(); shuffledHere = false }
        }
        // A reel's clip is the same file but not the same thing loaded: Watch
        // from here wants the whole video, from the top of its own timeline.
        if (_state.value.reel == null && _state.value.fileId == fileId && _state.value.error == null) {
            // Already loaded — the mini player keeps a film here after its
            // screen has gone, so this is the common case now, not a corner.
            // Asked for a time (a moment), go there; finished, start again;
            // otherwise carry on from where it is.
            val p = current()
            when {
                startMs != null -> p.seekTo(startMs)
                p.playbackState == Player.STATE_ENDED -> p.seekTo(0)
            }
            if (!holdStart && !p.isPlaying) p.play()
            // A running order handed over now (Play all on the same film) is
            // the order from here on: what plays next follows it.
            if (queue != null) scope.launch { refreshQueueView(fileId) }
            return
        }
        saveProgress()
        redrawOnNewSurface = false
        reelItems = emptyMap()
        _state.value = PlaybackState(loaded = true, fileId = fileId, speed = _state.value.speed, hardwareDecoding = _state.value.hardwareDecoding)
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
            discreet = prefs.appLock.first()
            val uri = resolver.playableUriFor(fileId)
            _state.update {
                it.copy(
                    title = file?.let { shownName(it) } ?: "",
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
            currentArt = artFor(ArtworkOwner.File(fileId))
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
     * Play a Moments reel: [clips] one after another, [title] being the
     * collection's name. They go to the player as one playlist of clipped
     * items, so the next clip loads while this one plays and there is no wait
     * between them. A reel is not watching: nothing is saved ([saveProgress]),
     * and the folder's Next and Keep playing do not apply.
     */
    fun loadReel(title: String, clips: List<ReelClip>, startIndex: Int = 0) {
        if (clips.isEmpty()) return
        Log.d(TAG, "loadReel($title, ${clips.size} clips)")
        saveProgress()
        holdStart = false
        redrawOnNewSurface = false
        activeQueue = emptyList()
        shuffledHere = false
        userChapterJob?.cancel()
        currentFile = null
        currentUri = null
        currentArt = null
        val index = startIndex.coerceIn(clips.indices)
        val first = clips[index]
        _state.value = PlaybackState(
            loaded = true, fileId = first.fileId, title = first.name, sourceLabel = first.videoName,
            durationMs = first.durationMs, speed = _state.value.speed, hardwareDecoding = _state.value.hardwareDecoding,
            reel = ReelState(title, clips, index),
        )
        scope.launch {
            val hardware = prefs.hardwareDecoding.first()
            if (hardware != _state.value.hardwareDecoding) {
                _state.update { it.copy(hardwareDecoding = hardware) }
                _player.value?.release()
                _player.value = null
            }
            discreet = prefs.appLock.first()
            val items = clips.associateWith { reelItem(it) }
            // Replaced while its items were being made: the newer one plays.
            val reel = _state.value.reel?.takeIf { it.clips == clips } ?: return@launch
            reelItems = items
            startReel(reel, positionMs = 0)
        }
    }

    /** The reel's clips as the player's items, made once ([loadReel]), so reordering never looks them up again. */
    private var reelItems: Map<ReelClip, MediaItem> = emptyMap()

    private suspend fun reelItem(clip: ReelClip): MediaItem = MediaItem.Builder()
        .setUri(resolver.playableUriFor(clip.fileId))
        .setMediaId("reel:${clip.fileId}:${clip.startMs}")
        .setClippingConfiguration(
            MediaItem.ClippingConfiguration.Builder().setStartPositionMs(clip.startMs).setEndPositionMs(clip.endMs).build(),
        )
        // The lock screen names the moment, and pictures it with its own frame.
        .setMediaMetadata(clipMetadata(clip, artFor(ArtworkOwner.Moment(clip.fileId, clip.startMs))))
        .build()

    /** Hand [reel]'s clips to the player, at the one playing and [positionMs] into it. */
    private fun startReel(reel: ReelState, positionMs: Long) {
        val p = current()
        p.repeatMode = Player.REPEAT_MODE_OFF
        p.setMediaItems(reel.clips.mapNotNull { reelItems[it] }, reel.index, positionMs)
        p.prepare()
        p.play()
        playerFileId = reel.clip.fileId
        _state.update { it.copy(playWhenReady = p.playWhenReady) }
    }

    /** Go to the reel's clip at [index], from its start, playing. */
    fun reelTo(index: Int) {
        val reel = _state.value.reel ?: return
        if (index !in reel.clips.indices) return
        val p = current()
        p.seekTo(index, 0)
        if (!p.playWhenReady) p.play()
    }

    /** The reel's next moment. */
    fun reelNext() {
        _state.value.reel?.takeIf { it.hasNext }?.let { reelTo(it.index + 1) }
    }

    /** The reel's previous moment; on the first, that moment from its start. */
    fun reelPrevious() {
        _state.value.reel?.let { reelTo((it.index - 1).coerceAtLeast(0)) }
    }

    /**
     * Shuffle what is left of the reel, or put it back in its order. The clip
     * playing carries on, and so does what was played: only the rest is
     * reordered, in the player's own list, so nothing stops to load again.
     */
    fun setReelShuffle(on: Boolean) {
        val reel = _state.value.reel ?: return
        if (reel.shuffled == on) return
        val p = _player.value ?: return
        val played = reel.clips.take(reel.index + 1)
        val rest = if (on) reel.clips.drop(reel.index + 1).shuffled() else reel.original.filterNot { it in played }
        if (p.mediaItemCount > reel.index + 1) p.removeMediaItems(reel.index + 1, p.mediaItemCount)
        p.addMediaItems(rest.mapNotNull { reelItems[it] })
        _state.update { it.copy(reel = reel.copy(clips = played + rest, shuffled = on)) }
    }

    /**
     * Watch from here: leave the reel for the whole video its clip is from,
     * carrying on from this very moment, with a resume point again.
     */
    fun watchReelFromHere() {
        val reel = _state.value.reel ?: return
        val intoClip = _player.value?.currentPosition ?: 0L
        load(reel.clip.fileId, startMs = reel.clip.startMs + intoClip)
    }

    /** A reel's clip on the lock screen: the moment's name over its video's, and its frame. */
    private fun clipMetadata(clip: ReelClip, art: ByteArray?): MediaMetadata {
        if (discreet) return MediaMetadata.Builder().setTitle("Regolith").setArtist("Playing").build()
        return MediaMetadata.Builder()
            .setTitle(clip.name)
            .setArtist(clip.videoName)
            .apply { art?.let { setArtworkData(it, MediaMetadata.PICTURE_TYPE_FRONT_COVER) } }
            .build()
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
        holdStart = false
        saveProgress()
        userChapterJob?.cancel()
        activeQueue = emptyList()
        currentFile = null
        currentUri = uri
        _state.value = PlaybackState(
            loaded = true,
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
            discreet = prefs.appLock.first()
            currentArt = null
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
            OnDemandScrubThumbnails(
                durationMs = file.durationMs ?: _state.value.durationMs,
                fileId = fileId,
                grabber = frameGrabber,
            ) {
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

    private fun MediaFileEntity.toNextItem() = NextItem(id, shownName(this), sizeBytes, durationMs)

    /**
     * What the player calls [file]: its file name without the extension, or
     * with spoof mode on the made-up one. Read once per load, like the rest
     * of the header; the real name is what the player still opens.
     */
    private fun shownName(file: MediaFileEntity): String =
        (spoof.current?.fileName(file.name) ?: file.name).substringBeforeLast('.')

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
            refreshQueueView(fileId)
        }
    }

    /**
     * A film ended with the player screen not in front of you. A queue, a
     * repeat, or Settings › Playback › Keep playing goes on to the next one
     * at once, with no card, since nobody is looking at one (the same rule as
     * the card's, [PlaybackState.playsOnTo]). With nothing after it, a film in
     * the mini player stops, closing it. One in its floating window, or under
     * a player screen with the screen off, is held at its end instead: the
     * window keeps its last frame until you close it, and the player is
     * waiting at the end when you come back.
     */
    private suspend fun atTheEnd() {
        if (screenOwnsTheEnd || !_state.value.playWhenReady) return
        val next = _state.value.playsOnTo(prefs.autoplayNext.first())
        when {
            next != null -> load(next.fileId)
            floating || playerScreens > 0 -> Unit
            else -> stop()
        }
    }

    /** What plays next and before, worked out again for [fileId] after the running order changed under it. */
    private suspend fun refreshQueueView(fileId: Long) {
        val view = queueView(fileId)
        if (_state.value.fileId != fileId) return
        _state.update {
            it.copy(
                next = view.next, previous = view.previous,
                wrapTo = view.wrapTo, wrapToLast = view.wrapToLast,
                queued = activeQueue.isNotEmpty(), shuffled = shuffledHere,
            )
        }
    }

    private fun startPlayer(fileId: Long?, file: MediaFileEntity?, positionMs: Long) {
        val item = MediaItem.Builder()
            .setUri(currentUri ?: resolver.uriFor(checkNotNull(fileId) { "no file and no uri" }))
            .setMediaId(fileId?.toString() ?: EXTERNAL_MEDIA_ID)
            // The lock screen and the notification shade show this (through
            // the media session), so spoof mode makes the title up there too.
            .setMediaMetadata(metadataFor(file?.let { shownName(it) } ?: _state.value.title))
            .build()
        val p = current()
        p.repeatMode = if (_state.value.repeat == RepeatMode.ONE) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        p.setMediaItem(item, positionMs)
        p.prepare()
        // Held, it is prepared all the same: the first frame is drawn paused.
        if (holdStart) p.pause() else p.play()
        playerFileId = fileId
        // Read back, not left to the listener: going on from a film that was
        // playing, play() changes nothing and reports nothing, and the fresh
        // state load() made would say paused while the next film plays.
        _state.update { it.copy(playWhenReady = p.playWhenReady) }
    }

    /** A film opened with `startPlaying = false` waits for [play]. */
    private var holdStart = false

    /** The film the player was last handed ([startPlayer]); null for one from another app. */
    private var playerFileId: Long? = null

    /**
     * Start the film [load] opened held, now that its picture has landed.
     * If the film is still being looked up, the hold is lifted and it starts
     * as soon as it is handed to the player; the player itself is not told
     * yet, since it may still hold the film before.
     */
    fun play() {
        holdStart = false
        if (playerFileId != _state.value.fileId) return
        val p = _player.value ?: return
        if (p.playbackState == Player.STATE_ENDED) p.seekTo(0)
        p.play()
    }

    /**
     * What the notification and the lock screen say about the film: its name,
     * the folder it lives in and its thumb — or, with the app lock on, only
     * "Regolith · Playing" (see [discreet]). Spoof mode gets no picture, as a
     * stock photo there would be one more thing to explain.
     */
    private fun metadataFor(title: String): MediaMetadata {
        if (discreet) return MediaMetadata.Builder().setTitle("Regolith").setArtist("Playing").build()
        val place = _state.value.sourceLabel.substringAfterLast(" · ").substringAfterLast('/').ifEmpty { null }
        return MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(place)
            .apply { currentArt?.let { setArtworkData(it, MediaMetadata.PICTURE_TYPE_FRONT_COVER) } }
            .build()
    }

    /**
     * [owner]'s thumb as bytes (a film's, or the frame of a reel's moment),
     * for the notification's picture, or null in spoof mode or before it has
     * been made. Bytes rather than the file's
     * address: the notification is drawn by the system, which cannot open a
     * file inside the app's own storage.
     */
    private suspend fun artFor(owner: ArtworkOwner): ByteArray? {
        if (spoof.current != null) return null
        val thumb = artwork.fileFor(artwork.relPathFor(owner, ArtworkKind.THUMB))
        return withContext(Dispatchers.IO) { thumb.takeIf { it.exists() }?.readBytes() }
    }

    /**
     * Where [file] lives, as the player's header, the mini player and the
     * lock screen show it ("MEDIA · Films/Arrival (2016)"). With spoof mode
     * on, the folders are made up, as on Title Detail and in Browse.
     */
    private suspend fun sourceLabelFor(file: MediaFileEntity): String {
        val folder = file.relPath.substringBeforeLast('/', "").let { spoof.current?.path(it) ?: it }
        val share = library.shareLabel(file.shareId)
        return listOf(share, folder).filter { it.isNotEmpty() }.joinToString(" · ")
    }

    // --- transport

    fun togglePlayPause() {
        val p = current()
        // Decide on intent, not on isPlaying: during a rebuffer isPlaying is
        // false but the user has not paused, and a tap then must pause.
        when {
            p.playbackState == Player.STATE_ENDED -> {
                // A finished reel starts again from its first clip, not its last.
                if (_state.value.reel != null) p.seekTo(0, 0) else p.seekTo(0)
                p.play()
            }
            p.playWhenReady -> p.pause()
            else -> p.play()
        }
    }

    /**
     * Draw a finished film's last frame again, on the next surface it is
     * given. A new surface — the player taking the film back from its
     * floating window — is given a frame by a paused film but not by one that
     * has ended, which has nothing left to decode, and would stay black.
     * Seeking to just before the end draws it, and the film ends again a
     * moment later, where it was. The seek waits for the surface itself
     * ([redrawOnNewSurface], answered in the listener), which Android makes a
     * frame or two after the screen asking for it: any sooner and the frame
     * is drawn into nothing. The screen going off needs no asking: the
     * listener sees the surface go and arms this itself.
     */
    fun redrawIfEnded() {
        val p = _player.value ?: return
        if (p.playbackState == Player.STATE_ENDED && p.duration > 0) redrawOnNewSurface = true
    }

    /** A finished film's last frame is wanted on the next surface that arrives ([redrawIfEnded]). */
    private var redrawOnNewSurface = false

    /** The player is drawing into a surface now (the last size the listener heard was not zero). */
    private var surfaceShown = false

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
        val reel = _state.value.reel
        when {
            reel != null -> startReel(reel, position)
            fileId != null -> startPlayer(fileId, currentFile, position)
        }
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
        // A reel is not watching: no resume points, nothing for Continue watching.
        if (_state.value.reel != null) return
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
        mediaSession?.release()
        mediaSession = null
        sessionPlayer = null
        context.stopService(Intent(context, PlaybackService::class.java))
        _player.value?.let {
            it.stop()
            it.clearMediaItems()
            it.release()
        }
        _player.value = null
        currentFile = null
        currentUri = null
        currentArt = null
        playerFileId = null
        holdStart = false
        reelItems = emptyMap()
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
            // Named chapters are the owner's own words: spoof mode makes them up
            // here, and keeps the editor shut so a made-up name is never saved.
            combine(userChapters.observe(fileId).spoofed(spoof) { chapters(it) }, userChapters.observeSync(fileId)) { marks, sync -> marks to sync }.collect { (marks, sync) ->
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
        /** A film's sound, for audio focus: paused by a call, ducked under a notification. */
        val MOVIE_AUDIO: AudioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()
        const val EXTERNAL_MEDIA_ID = "external"
        /** How far before the end [redrawIfEnded] goes back for a frame to draw. */
        const val REDRAW_BEFORE_END_MS = 80L
        const val TAG = "Regolith/Playback"
        const val TICK_MS = 250L
        const val SAVE_EVERY_TICKS = 20 // every 5 s while playing
        const val HOLD_SPEED = 2f
    }
}
