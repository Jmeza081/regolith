package com.regolith.player

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.effect.Presentation
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.inspector.frame.FrameExtractor
import com.regolith.data.artwork.FrameGrabber
import com.regolith.data.artwork.GrabbedFrame
import com.regolith.domain.smb.SeekableByteSource
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A still, taken by the same machinery that plays the file.
 *
 * `MediaMetadataRetriever` ([com.regolith.data.artwork.RetrieverFrameSource])
 * is cheaper and stays where cheap matters — the scrub previews, which take
 * dozens of frames while a finger moves. It has two weaknesses that only
 * hurt artwork, and both of them look the same from the outside: a tile
 * showing the first second of the film.
 *
 *  1. It is the *platform* extractor, not the one the player uses, so a
 *     container Regolith plays perfectly can still refuse to seek — and a
 *     refused seek is not reported, it just hands back the opening frame.
 *  2. It never says which frame it gave you, so nothing downstream can tell
 *     that it went wrong.
 *
 * Media3's `FrameExtractor` fixes both: the same extractors and the same
 * SMB data source as playback, and a [FrameExtractor.Frame.presentationTimeMs]
 * stating where the frame really came from. It costs an OpenGL context per
 * extraction, which is why it is used for one still per file and not for
 * scrubbing.
 *
 * Returns null rather than throwing when the platform cannot do it — no GL
 * context, no decoder, a timeout — and the caller falls back.
 */
@UnstableApi
@Singleton
class Media3Frames @Inject constructor(
    @ApplicationContext private val context: Context,
    private val smbDataSourceFactory: SmbDataSource.Factory,
    private val resolver: MediaUriResolver,
) : FrameGrabber {

    override suspend fun frameAt(fileId: Long, positionMs: Long, maxWidth: Int, maxHeight: Int): GrabbedFrame? {
        // Suspending lookup, so it happens before the blocking section; a copy
        // on this device yields a file:// URI and never touches the share.
        val uri = resolver.playableUriFor(fileId)
        return try {
            runInterruptible(Dispatchers.IO) {
                extractor(uri, maxWidth, maxHeight).use { extractor -> grab(extractor, positionMs) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "media3 frame at ${positionMs}ms for $fileId failed (${e.javaClass.simpleName}: ${e.message})")
            null
        }
    }

    /**
     * Every position on ONE extractor: one open of the file, one read of its
     * index, one decoder and GL context, then a seek per position. That setup
     * is most of what a single still costs over SMB, so a film's marks come
     * out for little more than the price of one.
     *
     * Each frame goes out as a COPY the caller owns and may recycle. The
     * extractor does not promise a new bitmap per request: when a seek lands
     * on the same key frame as the one before, it hands back that same frame
     * object again (Media3's `lastSeekDedupeFrame`). A caller recycling what
     * it was given was then recycling the extractor's own copy, and the next
     * request that landed there got a recycled bitmap — which is how the
     * Shorts strips, eight frames a few seconds apart in phone clips whose key
     * frames are further apart than that, crashed the app on open.
     *
     * Rendezvous, not buffered: the extractor waits for each frame to be
     * taken before fetching the next, so at most one full-size bitmap is ever
     * in flight, and a caller that stops collecting (a timeout, a screen that
     * went away) interrupts the grab rather than leaving it to run on.
     */
    override fun framesAt(fileId: Long, positionsMs: List<Long>, maxWidth: Int, maxHeight: Int): Flow<GrabbedFrame?> = channelFlow {
        if (positionsMs.isEmpty()) return@channelFlow
        val uri = resolver.playableUriFor(fileId)
        var sent = 0
        try {
            runInterruptible(Dispatchers.IO) {
                extractor(uri, maxWidth, maxHeight).use { extractor ->
                    for (position in positionsMs) {
                        // One frame failing is that frame's problem; the next
                        // seek on the same extractor may be fine.
                        val frame = try {
                            // The caller's own copy; the extractor may hand
                            // its original over again (see above).
                            val grabbed = grab(extractor, position)
                            grabbed.bitmap.copy(grabbed.bitmap.config ?: Bitmap.Config.ARGB_8888, false)
                                ?.let { GrabbedFrame(it, grabbed.presentationTimeMs) }
                        } catch (e: InterruptedException) {
                            throw e
                        } catch (e: Exception) {
                            Log.w(TAG, "media3 frame at ${position}ms for $fileId failed (${e.javaClass.simpleName}: ${e.message})")
                            null
                        }
                        trySendBlocking(frame).getOrThrow()
                        sent++
                    }
                }
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException || e is InterruptedException) throw e
            // The extractor itself would not build (no GL, no decoder, the
            // share refused the open): every position left is a miss.
            Log.w(TAG, "media3 extractor for $fileId failed (${e.javaClass.simpleName}: ${e.message})")
            repeat(positionsMs.size - sent) { send(null) }
        }
    }.buffer(Channel.RENDEZVOUS)

    /**
     * One film held open for the poster editor: every frame at FULL size and
     * at the EXACT time asked for, on one extractor that stays open between
     * requests. Caller closes it.
     *
     * Both differ from [frameAt] on purpose. A poster is cut from this
     * bitmap, so it is not scaled down, and "+1 frame" has to actually move
     * one frame, so the seek decodes forward from the key frame to the time
     * asked for rather than stopping at the key frame. That forward decode
     * is seconds of video pulled off the share, which is why it is only
     * done here, one frame at a time, while someone is looking.
     *
     * HDR is tone-mapped to ordinary (SDR) colour, FrameExtractor's default,
     * so a poster from an HDR film does not come out grey and washed out.
     */
    fun openExact(fileId: Long): ExactFrames = ExactFrames(fileId)

    inner class ExactFrames internal constructor(private val fileId: Long) : java.io.Closeable {
        private val lock = Any()
        private var extractor: FrameExtractor? = null
        private var busy = false
        private var closed = false
        private val turns = kotlinx.coroutines.sync.Mutex()

        /**
         * The frame at [positionMs], or null when it could not be decoded.
         * Callers take turns: a second call waits for the first.
         */
        suspend fun frameAt(positionMs: Long): GrabbedFrame? = turns.withLock {
            val uri = resolver.playableUriFor(fileId)
            try {
                runInterruptible(Dispatchers.IO) {
                    val ex = synchronized(lock) {
                        if (closed) return@runInterruptible null
                        busy = true
                        extractor ?: exactExtractor(uri).also { extractor = it }
                    }
                    try {
                        grab(ex, positionMs)
                    } finally {
                        // A close that arrived mid-grab was left to us: closing
                        // the extractor under a live request would wedge it.
                        synchronized(lock) {
                            busy = false
                            if (closed) closeExtractor()
                        }
                    }
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.w(TAG, "exact frame at ${positionMs}ms for $fileId failed (${e.javaClass.simpleName}: ${e.message})")
                null
            }
        }

        override fun close() {
            synchronized(lock) {
                closed = true
                if (!busy) closeExtractor()
            }
        }

        private fun closeExtractor() {
            runCatching { extractor?.close() }
            extractor = null
        }
    }

    private fun exactExtractor(uri: android.net.Uri): FrameExtractor {
        val mediaSources = DefaultMediaSourceFactory(context)
            .setDataSourceFactory(DefaultDataSource.Factory(context, smbDataSourceFactory))
        return FrameExtractor.Builder(context, MediaItem.fromUri(uri))
            .setMediaSourceFactory(mediaSources)
            .setSeekParameters(SeekParameters.EXACT)
            .build()
    }

    /**
     * The first frame of [open]'s bytes: a file with no index, cut to start in
     * its middle. Media3 opens it like any other file ([ByteSourceDataSource]),
     * finds it cannot seek, starts at the top, and the top is now the middle,
     * so the first frame it gives back is the one wanted, with the time it
     * really has in the film.
     */
    override suspend fun frameFromBytes(open: () -> SeekableByteSource, name: String, maxWidth: Int, maxHeight: Int): GrabbedFrame? = try {
        runInterruptible(Dispatchers.IO) {
            val mediaSources = DefaultMediaSourceFactory(context).setDataSourceFactory { ByteSourceDataSource(open) }
            // Only a name, for the extractors to guess the container by first.
            val uri = android.net.Uri.Builder().scheme("regolith-cut").authority("media").appendPath(name).build()
            // A cut's frame is its very first, a few hundred kilobytes in, so it
            // comes in well under a second. One that has not come in
            // [CUT_TIMEOUT_SECONDS] is not coming: a decoder that wedged as it
            // started (seen now and then on the emulator's software decoder,
            // which then holds up the next extractor too, so a second try is
            // no use) need not hold an extraction slot for Media3's thirty.
            frameExtractor(MediaItem.fromUri(uri), mediaSources, maxWidth, maxHeight).use { extractor -> grab(extractor, 0, CUT_TIMEOUT_SECONDS) }
        }
    } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        Log.w(TAG, "media3 frame from a cut of $name failed (${e.javaClass.simpleName}: ${e.message})")
        null
    }

    private fun extractor(uri: android.net.Uri, maxWidth: Int, maxHeight: Int): FrameExtractor {
        // A frame grab's own reading of the share (SmbDataSource.Reading.FRAMES):
        // the player's read-ahead would fetch a dozen megabytes per hop.
        val mediaSources = DefaultMediaSourceFactory(context)
            .setDataSourceFactory(DefaultDataSource.Factory(context, smbDataSourceFactory.forFrames()))
        return frameExtractor(MediaItem.fromUri(uri), mediaSources, maxWidth, maxHeight)
    }

    private fun frameExtractor(item: MediaItem, mediaSources: DefaultMediaSourceFactory, maxWidth: Int, maxHeight: Int): FrameExtractor {
        return FrameExtractor.Builder(context, item)
            .setMediaSourceFactory(mediaSources)
            // One key frame, like the retriever's OPTION_CLOSEST_SYNC:
            // decoding forward to an exact frame would mean pulling
            // seconds of video off the share for a thumbnail.
            .setSeekParameters(SeekParameters.CLOSEST_SYNC)
            // Scaled on the GPU during extraction, so a 4K film never
            // becomes a 33 MB bitmap on the way to a 320x180 tile.
            .setEffects(listOf(Presentation.createForWidthAndHeight(maxWidth, maxHeight, Presentation.LAYOUT_SCALE_TO_FIT)))
            .build()
    }

    private fun grab(extractor: FrameExtractor, positionMs: Long, timeoutSeconds: Long = TIMEOUT_SECONDS): GrabbedFrame {
        // Cancelled before `use` closes the extractor, not left running.
        // Closing one while a frame request is still outstanding means
        // tearing down the GL context and the decoder under work that still
        // believes it owns them, which is a far better way to wedge than to
        // fail.
        val pending = extractor.getFrame(positionMs)
        val frame = try {
            pending.get(timeoutSeconds, TimeUnit.SECONDS)
        } catch (e: Exception) {
            pending.cancel(/* mayInterruptIfRunning = */ true)
            throw e
        }
        return GrabbedFrame(frame.bitmap, frame.presentationTimeMs)
    }

    private companion object {
        const val TAG = "Regolith/Frames"

        /**
         * Long enough for a cold seek into a large file on a slow share,
         * short enough that a wall of tiles does not sit on a dead extractor.
         */
        const val TIMEOUT_SECONDS = 30L

        /** How long a cut's first frame ([frameFromBytes]) may take. */
        const val CUT_TIMEOUT_SECONDS = 12L
    }
}
