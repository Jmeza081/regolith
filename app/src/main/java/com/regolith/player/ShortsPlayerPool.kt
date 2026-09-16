package com.regolith.player

import android.content.Context
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.regolith.domain.playback.ShortsRing
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/**
 * A small ring of players so a swipe starts the next clip at once.
 *
 * **This is a deliberate, scoped exception to guardrail G4** ("one
 * ExoPlayer, owned by the app, not by a screen"). G4 exists because
 * playback must survive the Player screen being rotated, recreated or
 * left, and [PlaybackSession] remains the single owner of that everywhere
 * in the app. A feed is a different animal: opening a file over SMB costs
 * one to three seconds, so a single player would show a spinner between
 * every clip, which is not a feed but a slideshow. The exception is
 * confined to this screen, it holds nothing that must outlive it, and it
 * is released with the ViewModel.
 *
 * Deliberately NOT `@Singleton`: the pool belongs to the Shorts screen and
 * dies with it, so three decoders are never held by a screen nobody is on.
 *
 * Every player is built exactly the way [PlaybackSession] builds its one,
 * so the feed inherits SMB streaming, decoder fallback and the hardware
 * decoding preference rather than quietly diverging from the player.
 *
 * ExoPlayer is main-thread only, as it is in [PlaybackSession]: call all of
 * this from the UI.
 */
@UnstableApi
class ShortsPlayerPool @Inject constructor(
    @ApplicationContext private val context: Context,
    private val smbDataSourceFactory: SmbDataSource.Factory,
    private val resolver: MediaUriResolver,
) {
    val ring = ShortsRing()

    private val players = arrayOfNulls<ExoPlayer>(ring.size)

    /** Which feed position each slot currently holds, or [UNBOUND]. */
    private val bound = IntArray(ring.size) { UNBOUND }

    private var current = UNBOUND
    private var hardware = true

    /** The player showing feed position [index], or null when it is not in the window. */
    fun playerFor(index: Int): ExoPlayer? =
        players[ring.slotFor(index)]?.takeIf { bound[ring.slotFor(index)] == index }

    /**
     * Make [index] the playing clip, with its neighbours prepared but
     * silent and paused.
     *
     * [fileIds] is the whole feed in order; only the window is ever opened.
     * Positions leaving the window are stopped and their player reused, so
     * the decoder count never grows past the ring.
     */
    suspend fun bind(index: Int, fileIds: List<Long>, hardwareDecoding: Boolean) {
        if (hardwareDecoding != hardware) {
            // A different renderers factory means new players; there is no
            // way to switch a live one. Same reasoning as the session's.
            hardware = hardwareDecoding
            releaseAll()
        }
        val previous = current
        current = index

        for (gone in ring.released(previous, index, fileIds.size)) {
            val slot = ring.slotFor(gone)
            if (bound[slot] == gone) {
                players[slot]?.let { it.pause(); it.clearMediaItems() }
                bound[slot] = UNBOUND
            }
        }

        for (position in ring.window(index, fileIds.size)) {
            val slot = ring.slotFor(position)
            val player = players[slot] ?: createPlayer().also { players[slot] = it }
            if (bound[slot] != position) {
                // Resolved per clip: a downloaded copy plays from disk and
                // the feed never knows the difference (see MediaUriResolver).
                val uri = resolver.playableUriFor(fileIds[position])
                player.setMediaItem(MediaItem.fromUri(uri))
                player.prepare()
                bound[slot] = position
            }
            // Only the clip on screen makes a sound or advances. A prepared
            // neighbour that played would be audible over the one you are
            // actually watching.
            val onScreen = position == index
            player.volume = if (onScreen) 1f else 0f
            player.playWhenReady = onScreen
            if (!onScreen) player.seekTo(0)
        }
        Log.d(TAG, "bound $index of ${fileIds.size}; window=${ring.window(index, fileIds.size)}")
    }

    /** Play or pause the clip on screen. The neighbours are never touched. */
    fun togglePlayPause() {
        val player = playerFor(current) ?: return
        if (player.playbackState == Player.STATE_ENDED) {
            player.seekTo(0)
            player.play()
        } else if (player.playWhenReady) {
            player.pause()
        } else {
            player.play()
        }
    }

    /**
     * Stop making sound without giving up the buffers.
     *
     * For LEAVING the screen rather than pausing a clip: the window stays
     * prepared, so coming back starts again instantly instead of paying the
     * SMB open a second time.
     */
    fun pauseAll() {
        players.forEach { it?.pause() }
    }

    /** 2× while the finger is down, back to 1× on release. */
    fun holdFast(hold: Boolean) {
        playerFor(current)?.setPlaybackSpeed(if (hold) FAST_SPEED else 1f)
    }

    fun releaseAll() {
        players.indices.forEach { slot ->
            players[slot]?.release()
            players[slot] = null
            bound[slot] = UNBOUND
        }
        current = UNBOUND
    }

    private fun createPlayer(): ExoPlayer {
        // Identical to PlaybackSession.createPlayer: DefaultDataSource takes
        // file:// itself and hands regolith:// to the SMB factory.
        val dataSourceFactory = DefaultDataSource.Factory(context, smbDataSourceFactory)
        val renderers = DefaultRenderersFactory(context)
            .setEnableDecoderFallback(true)
            .setMediaCodecSelector(if (hardware) MediaCodecSelector.DEFAULT else MediaCodecSelector.PREFER_SOFTWARE)
        return ExoPlayer.Builder(context, renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(context).setDataSourceFactory(dataSourceFactory))
            .build()
            .also {
                // A short is watched more than once; a feed that stopped on a
                // ten-second clip would ask for a swipe you did not want yet.
                it.repeatMode = Player.REPEAT_MODE_ONE
            }
    }

    private companion object {
        const val UNBOUND = -1
        const val FAST_SPEED = 2f
        const val TAG = "Regolith/Shorts"
    }
}
