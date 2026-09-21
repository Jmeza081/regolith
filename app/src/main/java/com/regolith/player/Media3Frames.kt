package com.regolith.player

import android.content.Context
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
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
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
                val mediaSources = DefaultMediaSourceFactory(context)
                    .setDataSourceFactory(DefaultDataSource.Factory(context, smbDataSourceFactory))
                FrameExtractor.Builder(context, MediaItem.fromUri(uri))
                    .setMediaSourceFactory(mediaSources)
                    // One key frame, like the retriever's OPTION_CLOSEST_SYNC:
                    // decoding forward to an exact frame would mean pulling
                    // seconds of video off the share for a thumbnail.
                    .setSeekParameters(SeekParameters.CLOSEST_SYNC)
                    // Scaled on the GPU during extraction, so a 4K film never
                    // becomes a 33 MB bitmap on the way to a 320x180 tile.
                    .setEffects(listOf(Presentation.createForWidthAndHeight(maxWidth, maxHeight, Presentation.LAYOUT_SCALE_TO_FIT)))
                    .build()
                    .use { extractor ->
                        // Cancelled before `use` closes the extractor, not
                        // left running. Closing one while a frame request is
                        // still outstanding means tearing down the GL context
                        // and the decoder under work that still believes it
                        // owns them, which is a far better way to wedge than
                        // to fail.
                        val pending = extractor.getFrame(positionMs)
                        val frame = try {
                            pending.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                        } catch (e: Exception) {
                            pending.cancel(/* mayInterruptIfRunning = */ true)
                            throw e
                        }
                        GrabbedFrame(frame.bitmap, frame.presentationTimeMs)
                    }
            }
        } catch (e: Exception) {
            Log.w(TAG, "media3 frame at ${positionMs}ms for $fileId failed (${e.javaClass.simpleName}: ${e.message})")
            null
        }
    }

    private companion object {
        const val TAG = "Regolith/Frames"

        /**
         * Long enough for a cold seek into a large file on a slow share,
         * short enough that a wall of tiles does not sit on a dead extractor.
         */
        const val TIMEOUT_SECONDS = 30L
    }
}
