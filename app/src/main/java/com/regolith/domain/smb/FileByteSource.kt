package com.regolith.domain.smb

import java.io.File
import java.io.RandomAccessFile

/**
 * A file on this device, read the way the share is read: a copy that was
 * downloaded, the demo library's clips, the phone's own videos. No buffering
 * of its own, since the page cache is the buffer.
 */
class FileByteSource(file: File) : SeekableByteSource {
    private val handle = RandomAccessFile(file, "r")
    override val size: Long = handle.length()

    override fun readAt(offset: Long, dst: ByteArray, dstOffset: Int, length: Int): Int = synchronized(handle) {
        if (offset >= size) return -1
        handle.seek(offset)
        handle.read(dst, dstOffset, length)
    }

    override fun close() {
        runCatching { handle.close() }
    }
}
