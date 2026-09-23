package com.regolith.data.artwork

import android.graphics.Bitmap
import com.regolith.domain.smb.SmbCredentials
import com.regolith.domain.smb.SmbHost
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.Closeable
import java.io.File

/**
 * One open video, asked for pictures. Behind this sit the platform's
 * `MediaMetadataRetriever` today ([RetrieverFrameSource]) and, if it ever
 * proves too slow or too picky about containers, Media3's `FrameExtractor`;
 * the artwork pipeline and the scrub previews only know this.
 *
 * Every call blocks on the network and decoding: use it on `Dispatchers.IO`.
 * Close it when done; it holds an SMB file handle.
 */
interface FrameSource : Closeable {
    /** Runtime as the container reports it, or null when it does not say. */
    val durationMs: Long?
    val width: Int?
    val height: Int?

    /**
     * Clockwise degrees the container asks for on the way to the screen: 0,
     * 90, 180 or 270, or null when it does not say.
     *
     * [width] and [height] are the STORED size, which on its own says
     * nothing about which way up the picture is: phone video is routinely
     * stored landscape with a 90 here.
     */
    val rotationDegrees: Int?

    /** Cover art stored in the container (MP4 `covr`), as encoded bytes. */
    fun embeddedPicture(): ByteArray?

    /**
     * The key frame nearest [positionMs], scaled to fit within
     * [maxWidth]×[maxHeight]. Null when the frame cannot be decoded.
     */
    fun frameAt(positionMs: Long, maxWidth: Int, maxHeight: Int): Bitmap?
}

/** Opens a [FrameSource] on one file of a share. Throws [com.regolith.domain.smb.SmbFailure]. */
interface FrameSourceFactory {
    fun open(host: SmbHost, credentials: SmbCredentials, share: String, relPath: String): FrameSource

    /**
     * The same, on a copy already on this device (a download, or the demo
     * library). No network, so no [com.regolith.domain.smb.SmbFailure] and
     * no read-ahead worth the name.
     */
    fun openLocal(file: File): FrameSource
}

/**
 * "How long is this file, really?"
 *
 * The platform's `MediaMetadataRetriever` does not always say — some
 * containers, some muxers, and the answer comes back null. That used to
 * mean a frame grabbed at position 0, which is a black title card or a
 * studio ident: the exact tile the midpoint grab exists to avoid. So the
 * pipeline falls back to Media3's extractors, the same ones the player
 * uses, through this seam.
 *
 * An interface rather than a direct call so `data/artwork` does not have
 * to depend on Media3, and so a test can hand it a number.
 */
fun interface DurationProbe {
    /** Milliseconds, or null when even the full probe cannot say. Blocks on the share. */
    suspend fun durationMs(fileId: Long): Long?
}

/** A frame, and the position it ACTUALLY came from — not the one that was asked for. */
class GrabbedFrame(val bitmap: android.graphics.Bitmap, val presentationTimeMs: Long)

/**
 * One still, taken the way the player would take it.
 *
 * Separate from [FrameSource] because it answers a different question: a
 * FrameSource is an open file you ask repeatedly (the scrub previews), and
 * this is one frame with a receipt saying where it came from. That receipt
 * is the whole point — the platform extractor silently returns the opening
 * frame when it cannot seek, and nothing downstream could tell.
 *
 * An interface so `data/artwork` keeps no Media3 dependency, and so a test
 * can hand back whatever it likes.
 */
fun interface FrameGrabber {
    /** Null when the platform cannot do it at all; the caller then falls back. */
    suspend fun frameAt(fileId: Long, positionMs: Long, maxWidth: Int, maxHeight: Int): GrabbedFrame?

    /**
     * Several stills from ONE film, in the order of [positionsMs]: exactly one
     * emission per position, null where that frame could not be had.
     *
     * The point is the setup cost. Opening a file over SMB, reading its
     * index and standing up a decoder is most of what one still costs, and
     * [frameAt] pays it every time — so the marks in one film are grabbed on
     * one open. A Flow rather than a list so each frame can be saved (and its
     * bitmap freed) as it lands, and so frames already taken survive a
     * timeout that cuts the rest short.
     *
     * The default is the slow way round, one [frameAt] each, for a grabber
     * (or a test double) with nothing to share between frames.
     */
    fun framesAt(fileId: Long, positionsMs: List<Long>, maxWidth: Int, maxHeight: Int): Flow<GrabbedFrame?> = flow {
        for (position in positionsMs) emit(frameAt(fileId, position, maxWidth, maxHeight))
    }
}
