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
import com.regolith.domain.playback.AbLoop
import com.regolith.domain.playback.VideoInfo
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
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
    /** Chapter start positions. Empty until the container probe (Phase 4). */
    val chaptersMs: List<Long> = emptyList(),
    val error: String? = null,
)

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
    private var lastSavedPositionMs = -1L
    private var currentFile: MediaFileEntity? = null
    /** file:// for a copy on this device, regolith:// for the share. */
    private var currentUri: android.net.Uri? = null

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
    fun load(fileId: Long, startMs: Long? = null) {
        Log.d(TAG, "load($fileId, $startMs) current=${_state.value.fileId}")
        if (_state.value.fileId == fileId && _state.value.error == null) {
            if (!current().isPlaying) current().play()
            return
        }
        saveProgress()
        _state.value = PlaybackState(fileId = fileId, speed = _state.value.speed, hardwareDecoding = _state.value.hardwareDecoding)
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
            val next = library.filesAfter(fileId).map { NextItem(it.id, it.name.substringBeforeLast('.'), it.sizeBytes, it.durationMs) }
            val uri = resolver.playableUriFor(fileId)
            _state.update {
                it.copy(
                    title = file?.name?.substringBeforeLast('.') ?: "",
                    sourceLabel = if (resolver.isLocal(uri)) "On this device" else file?.let { f -> sourceLabelFor(f) } ?: "",
                    fileSizeBytes = file?.sizeBytes ?: 0,
                    next = next,
                )
            }
            currentUri = uri
            startPlayer(fileId, file, resume)
            setScrubThumbnails(prefs.scrubThumbnails.first())
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

    private fun startPlayer(fileId: Long, file: MediaFileEntity?, positionMs: Long) {
        val item = MediaItem.Builder()
            .setUri(currentUri ?: resolver.uriFor(fileId))
            .setMediaId(fileId.toString())
            .setMediaMetadata(MediaMetadata.Builder().setTitle(file?.name).build())
            .build()
        val p = current()
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
        const val TAG = "Regolith/Playback"
        const val TICK_MS = 250L
        const val SAVE_EVERY_TICKS = 20 // every 5 s while playing
        const val HOLD_SPEED = 2f
    }
}
