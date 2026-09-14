package com.regolith.desktop.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.regolith.domain.smb.SeekableByteSource
import uk.co.caprica.vlcj.media.callback.DefaultCallbackMedia
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter
import uk.co.caprica.vlcj.player.component.CallbackMediaPlayerComponent
import javax.swing.JComponent

/**
 * The Mac app's player: the desktop stand-in for the phone's
 * `PlaybackSession` + `SmbDataSource`.
 *
 * ExoPlayer does not exist off Android, so the Mac plays through libvlc
 * (vlcj binds it over JNA). Bytes still come through the shared
 * [SeekableByteSource] from `SmbGateway.open`, adapted by
 * [SeekableByteSourceMedia] the way the phone adapts it for ExoPlayer, so
 * VLC's own SMB code is never involved and both apps read a share the same way.
 *
 * Frames are painted into a Swing component ([surface]) that Compose hosts
 * with `SwingPanel`; macOS has no embeddable native VLC view. Web analogy: a
 * `<video>` decoded into a `<canvas>`.
 *
 * Position, duration and playing are Compose state, updated from VLC's
 * event thread; reads in composition are safe, writes happen only here.
 */
class VlcPlayer : FilmPlayer {
    private val component = CallbackMediaPlayerComponent()
    private val player: MediaPlayer get() = component.mediaPlayer()

    /** The Swing view to place inside a `SwingPanel`. */
    val surface: JComponent get() = component

    override var positionMs by mutableStateOf(0L); private set
    override var durationMs by mutableStateOf(0L); private set
    override var playing by mutableStateOf(false); private set
    override var error by mutableStateOf<String?>(null); private set

    private var source: SeekableByteSource? = null

    // libvlc keeps only a weak native handle to the read callbacks. If the JVM
    // object were collected mid-play, reads would stop ("callback object has
    // been garbage collected"), so the player owns it for as long as it plays.
    private var media: SeekableByteSourceMedia? = null

    init {
        player.events().addMediaPlayerEventListener(object : MediaPlayerEventAdapter() {
            override fun timeChanged(mediaPlayer: MediaPlayer, newTime: Long) { positionMs = newTime }
            override fun lengthChanged(mediaPlayer: MediaPlayer, newLength: Long) { durationMs = newLength }
            override fun playing(mediaPlayer: MediaPlayer) { playing = true }
            override fun paused(mediaPlayer: MediaPlayer) { playing = false }
            override fun stopped(mediaPlayer: MediaPlayer) { playing = false }
            override fun finished(mediaPlayer: MediaPlayer) { playing = false }
            override fun error(mediaPlayer: MediaPlayer) { error = "This film could not be played"; playing = false }
        })
    }

    override fun open(src: SeekableByteSource) {
        release()
        source = src
        error = null
        positionMs = 0
        durationMs = 0
        media = SeekableByteSourceMedia(src).also { player.media().play(it) }
    }

    override fun togglePause() {
        if (playing) player.controls().pause() else player.controls().play()
    }

    /** Clamped to the film. The clock moves at once so the UI does not wait for VLC's next tick. */
    override fun seekTo(ms: Long) {
        val to = if (durationMs > 0) ms.coerceIn(0, durationMs) else ms.coerceAtLeast(0)
        player.controls().setTime(to)
        positionMs = to
    }

    override fun release() {
        runCatching { player.controls().stop() }
        source?.close()
        source = null
        media = null
    }

    /** Release the native player for good. Call once, when the window closes. */
    fun dispose() {
        release()
        component.release()
    }
}

/**
 * libvlc "callback media": VLC asks us to open, read, seek and close instead
 * of opening a URL itself. Same shape as an ExoPlayer `DataSource`.
 */
internal class SeekableByteSourceMedia(private val src: SeekableByteSource) : DefaultCallbackMedia(true) {
    private var position = 0L

    override fun onGetSize(): Long = src.size

    override fun onOpen(): Boolean {
        position = 0
        return true
    }

    override fun onRead(buffer: ByteArray, bufferSize: Int): Int {
        val n = src.readAt(position, buffer, 0, bufferSize)
        if (n > 0) position += n
        return n
    }

    override fun onSeek(offset: Long): Boolean {
        position = offset
        return true
    }

    override fun onClose() {
        // The owner (VlcPlayer) closes the source.
    }
}
