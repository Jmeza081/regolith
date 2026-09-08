package com.regolith.player

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.regolith.data.repository.LibraryRepository
import com.regolith.data.repository.PlaybackRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** What the Player screen draws. */
data class PlaybackState(
    val fileId: Long? = null,
    val title: String = "",
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val positionMs: Long = 0,
    val bufferedMs: Long = 0,
    val durationMs: Long = 0,
    val error: String? = null,
)

/**
 * The one ExoPlayer, owned by the app rather than by a screen (guardrail
 * G4). The Player screen observes [state] and sends commands; if it is
 * rotated, recreated or left, playback and progress saving carry on here.
 * That is also what lets picture-in-picture or a media session be added
 * later without touching the screen.
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
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _state = MutableStateFlow(PlaybackState())
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private var ticker: Job? = null
    private var lastSavedPositionMs = -1L

    /** Created on first use; released on [release]. Exposed for the video surface only. */
    val player: ExoPlayer by lazy {
        // DefaultDataSource handles file:// (Phase 5 downloads) itself and
        // hands every other scheme, i.e. regolith://, to our SMB factory.
        val dataSourceFactory = DefaultDataSource.Factory(context, smbDataSourceFactory)
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(context).setDataSourceFactory(dataSourceFactory))
            .build()
            .also { it.addListener(listener) }
    }

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _state.update { it.copy(isPlaying = isPlaying) }
            if (isPlaying) startTicker() else saveProgress()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            _state.update {
                it.copy(
                    isBuffering = playbackState == Player.STATE_BUFFERING,
                    durationMs = player.duration.coerceAtLeast(0),
                )
            }
            if (playbackState == Player.STATE_ENDED) saveProgress()
        }

        override fun onPlayerError(error: PlaybackException) {
            _state.update { it.copy(error = error.message ?: error.errorCodeName, isPlaying = false) }
        }
    }

    /**
     * Load and play a file. Resumes from the saved position unless
     * [startMs] says otherwise. Safe to call for the file already loaded.
     */
    fun load(fileId: Long, startMs: Long? = null) {
        if (_state.value.fileId == fileId && _state.value.error == null) {
            if (!player.isPlaying) player.play()
            return
        }
        saveProgress()
        _state.value = PlaybackState(fileId = fileId)
        scope.launch {
            val file = library.file(fileId)
            val resume = startMs ?: playback.progress(fileId)?.takeUnless { it.completed }?.positionMs ?: 0L
            _state.update { it.copy(title = file?.name?.substringBeforeLast('.') ?: "") }
            val item = MediaItem.Builder()
                .setUri(resolver.uriFor(fileId))
                .setMediaId(fileId.toString())
                .setMediaMetadata(MediaMetadata.Builder().setTitle(file?.name).build())
                .build()
            player.setMediaItem(item, resume)
            player.prepare()
            player.play()
        }
    }

    fun togglePlayPause() {
        if (player.isPlaying) player.pause() else player.play()
    }

    fun seekTo(positionMs: Long) {
        player.seekTo(positionMs.coerceIn(0, player.duration.coerceAtLeast(0)))
        _state.update { it.copy(positionMs = player.currentPosition) }
    }

    /** Persist the current position now (screen leaving, app backgrounded). */
    fun saveProgress() {
        val fileId = _state.value.fileId ?: return
        val position = player.currentPosition
        val duration = player.duration
        if (duration <= 0 || position == lastSavedPositionMs) return
        lastSavedPositionMs = position
        scope.launch(Dispatchers.IO) { playback.save(fileId, position, duration) }
    }

    /** Stop and free the decoder. Next [load] creates a fresh player. */
    fun stop() {
        saveProgress()
        ticker?.cancel()
        player.stop()
        player.clearMediaItems()
        _state.value = PlaybackState()
    }

    private fun startTicker() {
        ticker?.cancel()
        ticker = scope.launch {
            var sinceSave = 0
            while (isActive && player.isPlaying) {
                _state.update {
                    it.copy(
                        positionMs = player.currentPosition,
                        bufferedMs = player.bufferedPosition,
                        durationMs = player.duration.coerceAtLeast(0),
                    )
                }
                if (++sinceSave >= SAVE_EVERY_TICKS) {
                    sinceSave = 0
                    saveProgress()
                }
                delay(TICK_MS)
            }
        }
    }

    private companion object {
        const val TICK_MS = 500L
        const val SAVE_EVERY_TICKS = 10 // every 5 s while playing
    }
}
