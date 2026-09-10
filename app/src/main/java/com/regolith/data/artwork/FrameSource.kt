package com.regolith.data.artwork

import android.graphics.Bitmap
import com.regolith.domain.smb.SmbCredentials
import com.regolith.domain.smb.SmbHost
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
