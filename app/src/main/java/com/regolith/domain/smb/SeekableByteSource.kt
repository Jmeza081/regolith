package com.regolith.domain.smb

import java.io.Closeable

/**
 * Random-access read handle over one remote file. This is the narrowest
 * contract the player, the frame extractor and the download worker need,
 * so it is the seam that keeps the SMB library swappable (guardrail G1).
 *
 * Implementations are blocking and must only be used off the main thread
 * (ExoPlayer calls them from its own loader thread).
 */
interface SeekableByteSource : Closeable {
    /** Total length in bytes. */
    val size: Long

    /**
     * Read up to [length] bytes starting at absolute [offset] into [dst] at
     * [dstOffset]. Returns the number of bytes read, or -1 at end of file.
     * May return fewer than [length] bytes; callers loop.
     */
    fun readAt(offset: Long, dst: ByteArray, dstOffset: Int, length: Int): Int
}
