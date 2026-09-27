package com.regolith.domain.smb

import java.io.Closeable

/**
 * One remote file being written front to back: the write side of
 * [SeekableByteSource], and the narrowest contract an upload needs.
 *
 * It APPENDS. A file that already has bytes in it — a `.part` left by an
 * upload the network interrupted — is carried on from its end, and
 * [startOffset] says where that is, so the caller can open its own source
 * at the same place. That is the whole of resuming: no byte goes over the
 * wire twice, and nothing has to be remembered on the phone except the name.
 *
 * Blocking; only use it off the main thread. Failures arrive as
 * [SmbFailure], mapped exactly as every other gateway call maps them.
 */
interface SmbWriteSink : Closeable {
    /** Bytes the file already held when it was opened: where this write carries on from. */
    val startOffset: Long

    /** Append [length] bytes of [src] from [offset]. */
    fun write(src: ByteArray, offset: Int, length: Int)
}
