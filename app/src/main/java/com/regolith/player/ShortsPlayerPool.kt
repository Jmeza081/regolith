package com.regolith.player

import android.content.Context
import android.util.Log
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
 * Every player is built the way [PlaybackSession] builds its one, so the
 * feed inherits SMB streaming, decoder fallback and the hardware decoding
 * preference rather than quietly diverging from the player. The one
 * addition is in front of the share: the openings fetched ahead of time
 * ([ShortsOpenings]), read first, so the clips a visit opens on start
 * from disk.
 *
 * ExoPlayer is main-thread only, as it is in [PlaybackSession]: call all of
 * this from the UI.
 */
@UnstableApi
class ShortsPlayerPool @Inject constructor(
    @ApplicationContext private val context: Context,
    private val smbDataSourceFactory: SmbDataSource.Factory,
    private val resolver: MediaUriResolver,
    private val openings: ShortsOpenings,
) {
    val ring = ShortsRing()

    private val players = arrayOfNulls<ExoPlayer>(ring.size)

    /** Which feed position each slot currently holds, or [UNBOUND]. */
    private val bound = IntArray(ring.size) { UNBOUND }

    /**
     * Which clip each slot holds. A position alone is not enough: a reshuffle
     * or a new folder puts a different clip at position 0, and a slot that
     * only remembered "0" kept playing the old clip under the new title.
     */
    private val boundClip = arrayOfNulls<ShortsClip>(ring.size)

    /** When each slot was handed its clip, for the first-frame log; 0 once logged. */
    private val boundAtNs = LongArray(ring.size)

    private var current = UNBOUND
    private var hardware = true

    /** The player showing feed position [index], or null when it is not in the window. */
    fun playerFor(index: Int): ExoPlayer? =
        players[ring.slotFor(index)]?.takeIf { bound[ring.slotFor(index)] == index }

    /**
     * Make [index] the playing clip, with its neighbours prepared but
     * silent and paused.
     *
     * [clips] is the whole feed in order; only the window is ever opened.
     * Positions leaving the window are stopped and their player reused, so
     * the decoder count never grows past the ring.
     */
    suspend fun bind(index: Int, clips: List<ShortsClip>, hardwareDecoding: Boolean) {
        if (hardwareDecoding != hardware) {
            // A different renderers factory means new players; there is no
            // way to switch a live one. Same reasoning as the session's.
            hardware = hardwareDecoding
            releaseAll()
        }
        val previous = current
        current = index

        for (gone in ring.released(previous, index, clips.size)) {
            val slot = ring.slotFor(gone)
            if (bound[slot] == gone) {
                players[slot]?.let { it.pause(); it.clearMediaItems() }
                bound[slot] = UNBOUND
                boundClip[slot] = null
            }
        }

        for (position in ring.window(index, clips.size)) {
            val slot = ring.slotFor(position)
            val player = players[slot] ?: createPlayer(slot).also { players[slot] = it }
            val clip = clips[position]
            if (bound[slot] != position || boundClip[slot]?.fileId != clip.fileId) {
                // Resolved per clip: a downloaded copy plays from disk and
                // the feed never knows the difference (see MediaUriResolver).
                val uri = resolver.playableUriFor(clip.fileId)
                player.setMediaItem(openings.mediaItem(clip, uri))
                player.prepare()
                bound[slot] = position
                boundClip[slot] = clip
                boundAtNs[slot] = System.nanoTime()
            }
            // Only the clip on screen makes a sound or advances. A prepared
            // neighbour that played would be audible over the one you are
            // actually watching.
            val onScreen = position == index
            player.volume = if (onScreen) 1f else 0f
            player.playWhenReady = onScreen
            if (!onScreen) player.seekTo(0)
        }
        Log.d(TAG, "bound $index of ${clips.size}; window=${ring.window(index, clips.size)}")
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
     * Jump the clip on screen to [positionMs], leaving it playing or paused
     * as it was: the Shorts panel's filmstrip is a way to get somewhere, not
     * a pause button.
     */
    fun seekTo(positionMs: Long) {
        playerFor(current)?.seekTo(positionMs)
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
            boundClip[slot] = null
            boundAtNs[slot] = 0L
        }
        current = UNBOUND
    }

    private fun createPlayer(slot: Int): ExoPlayer {
        // As PlaybackSession.createPlayer — DefaultDataSource takes file://
        // itself and hands regolith:// to the SMB factory — with the openings
        // on disk read before either.
        val dataSourceFactory = openings.readThrough(DefaultDataSource.Factory(context, smbDataSourceFactory))
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
                it.addListener(object : Player.Listener {
                    override fun onRenderedFirstFrame() = logFirstFrame(slot)
                })
            }
    }

    /**
     * How long the clip in [slot] took to show a picture, and how much of it
     * was on disk: the number [ShortsOpenings] exists to shrink. Read it on
     * the phone with `adb logcat -s Regolith/Shorts`.
     */
    private fun logFirstFrame(slot: Int) {
        val clip = boundClip[slot] ?: return
        val since = boundAtNs[slot].takeIf { it != 0L } ?: return
        // Once per binding: a seek or a loop renders a "first" frame too.
        boundAtNs[slot] = 0L
        val ms = (System.nanoTime() - since) / 1_000_000
        Log.d(TAG, "file ${clip.fileId}: first frame ${ms}ms after binding, ${openings.bytesOnDisk(clip) / 1024} KB of it on disk")
    }

    private companion object {
        const val UNBOUND = -1
        const val FAST_SPEED = 2f
        const val TAG = "Regolith/Shorts"
    }
}
