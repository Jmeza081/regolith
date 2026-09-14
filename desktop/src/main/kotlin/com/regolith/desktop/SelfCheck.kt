package com.regolith.desktop

import com.regolith.desktop.player.NativeVlc
import com.regolith.desktop.player.SeekableByteSourceMedia
import com.regolith.domain.smb.SmbCredentials
import com.regolith.domain.smb.SmbGateway
import com.regolith.domain.smb.SmbHost
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter
import uk.co.caprica.vlcj.player.embedded.videosurface.CallbackVideoSurface
import uk.co.caprica.vlcj.player.embedded.videosurface.VideoSurfaceAdapters
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormat
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormatCallbackAdapter
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.RenderCallbackAdapter
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.format.RV32BufferFormat
import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO

/**
 * Proves that the libvlc this process loads can play a film from a share,
 * with no window: open through the shared SMB client, wait for the length,
 * seek to [seekMs], and optionally save the decoded frame as a PNG.
 *
 * `Regolith Chapters --self-check smb://host/share/path.mp4 frame.png` runs
 * it inside the packaged app, which is the only way to check that the
 * bundled libvlc, its plugins and the trimmed Java runtime all work together.
 * `:desktop:smoke` runs it from the checkout.
 */
internal object SelfCheck {
    data class Report(val libvlc: String, val lengthMs: Long, val landedMs: Long, val frame: File?)

    fun play(gateway: SmbGateway, host: SmbHost, credentials: SmbCredentials, share: String, relPath: String, seekMs: Long = 240_000, frameOut: File? = null): Report {
        val libvlc = checkNotNull(NativeVlc.load()) { "libvlc was not found" }
        val src = gateway.open(host, credentials, share, relPath)
        val factory = MediaPlayerFactory("--no-audio", "--quiet")
        val player = factory.mediaPlayers().newEmbeddedMediaPlayer()
        val media = SeekableByteSourceMedia(src)
        try {
            var frame: BufferedImage? = null
            val render = object : RenderCallbackAdapter() {
                override fun onDisplay(mediaPlayer: MediaPlayer, buffer: IntArray) {
                    val img = frame ?: return
                    System.arraycopy(buffer, 0, (img.raster.dataBuffer as DataBufferInt).data, 0, minOf(buffer.size, img.width * img.height))
                }
            }
            val formats = object : BufferFormatCallbackAdapter() {
                override fun getBufferFormat(sourceWidth: Int, sourceHeight: Int): BufferFormat {
                    frame = BufferedImage(sourceWidth, sourceHeight, BufferedImage.TYPE_INT_RGB)
                    render.setBuffer(IntArray(sourceWidth * sourceHeight))
                    return RV32BufferFormat(sourceWidth, sourceHeight)
                }
            }
            player.videoSurface().set(CallbackVideoSurface(formats, render, true, VideoSurfaceAdapters.getVideoSurfaceAdapter()))

            val gotLength = CountDownLatch(1)
            var length = 0L
            var clock = 0L
            player.events().addMediaPlayerEventListener(object : MediaPlayerEventAdapter() {
                override fun lengthChanged(mediaPlayer: MediaPlayer, newLength: Long) { length = newLength; gotLength.countDown() }
                override fun timeChanged(mediaPlayer: MediaPlayer, newTime: Long) { clock = newTime }
            })
            player.media().play(media)
            check(gotLength.await(20, TimeUnit.SECONDS)) { "libvlc never reported a length" }
            player.controls().setTime(seekMs)
            Thread.sleep(2_500)
            val written = frameOut?.let { out -> frame?.let { out.absoluteFile.parentFile.mkdirs(); ImageIO.write(it, "png", out); out } }
            player.controls().stop()
            return Report(libvlc, length, clock, written)
        } finally {
            player.release()
            factory.release()
            src.close()
            media.hashCode() // held to here on purpose: libvlc's handle to it is weak
        }
    }

    /** `smb://host[:port]/share/path/to/film.mp4` → its parts; guest only. */
    fun parse(url: String): Triple<SmbHost, String, String> {
        val parts = url.removePrefix("smb://").split('/').filter { it.isNotEmpty() }
        require(parts.size >= 3) { "Expected smb://host/share/path/to/film, got $url" }
        val hostPart = parts[0]
        val host = hostPart.substringBefore(':')
        val port = hostPart.substringAfter(':', "").toIntOrNull() ?: SmbHost.DEFAULT_PORT
        return Triple(SmbHost(host, port), parts[1], parts.drop(2).joinToString("/"))
    }
}
