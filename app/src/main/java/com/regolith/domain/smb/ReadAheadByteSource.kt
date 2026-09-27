package com.regolith.domain.smb

import java.io.IOException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.ThreadFactory

/**
 * Sequential read-ahead with several reads on the wire at once.
 *
 * Why: jcifs-ng sends a large read as a chain of ~128 KB SMB requests, one after another,
 * so a single handle waits one Wi-Fi round trip per ~128 KB. On a Quest 3 that capped a
 * handle at about 40 Mbps whatever the block size, while a 130 Mbps file needs more
 * (spike S3). Fetching the next [depth] blocks in parallel, each on its own handle to the
 * same file, keeps the pipe full.
 *
 * Reads are served from [blockSize]-byte blocks. A read at offset X waits for X's block and
 * makes sure the [depth] blocks after it are being fetched; blocks behind the reader are
 * dropped, so memory stays at about (depth + 1) blocks. A seek simply starts a new window.
 *
 * Thread safe: the window is guarded by a lock, and each fetch borrows a handle from a
 * pool, so no two threads use one handle (a jcifs handle keeps a file pointer).
 *
 * @param first an open handle to the file; it also answers [size].
 * @param openAnother opens one more handle to the same file, called at most `depth - 1` times.
 */
class ReadAheadByteSource(
    first: SeekableByteSource,
    private val openAnother: () -> SeekableByteSource,
    private val blockSize: Int = DEFAULT_BLOCK_SIZE,
    private val depth: Int = DEFAULT_DEPTH,
) : SeekableByteSource {

    override val size: Long = first.size

    private class Block(val data: ByteArray, val length: Int)

    private val lock = Any()
    private val blocks = HashMap<Long, Future<Block?>>()
    private val idle = ArrayBlockingQueue<SeekableByteSource>(depth).apply { add(first) }
    private val all = mutableListOf(first)
    private var opened = 1
    @Volatile private var closed = false

    private val pool = Executors.newFixedThreadPool(depth, ThreadFactory { r ->
        Thread(r, "smb-read-ahead").apply { isDaemon = true }
    })

    override fun readAt(offset: Long, dst: ByteArray, dstOffset: Int, length: Int): Int {
        if (length == 0) return 0
        if (offset >= size) return -1
        val start = offset - offset % blockSize
        val future = synchronized(lock) {
            if (closed) throw IOException("closed")
            // Drop what the reader has passed (keep one block behind for small back-seeks)
            // and anything beyond the new window after a seek.
            val keepFrom = start - blockSize
            val keepTo = start + depth.toLong() * blockSize
            blocks.keys.filter { it < keepFrom || it > keepTo }.forEach { blocks.remove(it)?.cancel(false) }
            var s = start
            while (s < size && s <= keepTo) {
                if (s !in blocks) { val at = s; blocks[at] = pool.submit<Block?> { fetch(at) } }
                s += blockSize
            }
            blocks.getValue(start)
        }
        val block = try {
            future.get()
        } catch (e: ExecutionException) {
            synchronized(lock) { blocks.remove(start) } // let a retry fetch it again
            // Rethrow what the fetch threw, so SmbDataSource still sees an SmbFailure and maps it.
            throw when (val cause = e.cause) {
                is SmbFailure, is IOException, is RuntimeException -> cause
                else -> IOException(cause)
            }
        } ?: return -1
        val inBlock = (offset - start).toInt()
        if (inBlock >= block.length) return -1
        val n = minOf(length, block.length - inBlock)
        System.arraycopy(block.data, inBlock, dst, dstOffset, n)
        return n
    }

    private fun fetch(start: Long): Block? {
        val handle = borrow()
        try {
            val want = minOf(blockSize.toLong(), size - start).toInt()
            val data = ByteArray(want)
            var got = 0
            while (got < want) {
                val n = handle.readAt(start + got, data, got, want - got)
                if (n <= 0) break
                got += n
            }
            return if (got == 0) null else Block(data, got)
        } finally {
            idle.offer(handle)
        }
    }

    /** An idle handle, opening a new one while fewer than [depth] exist. */
    private fun borrow(): SeekableByteSource {
        idle.poll()?.let { return it }
        val openNew = synchronized(lock) { if (opened < depth && !closed) { opened++; true } else false }
        if (openNew) return openAnother().also { synchronized(lock) { all += it } }
        return idle.take()
    }

    override fun close() {
        val handles = synchronized(lock) {
            closed = true
            blocks.values.forEach { it.cancel(false) }
            blocks.clear()
            all.toList()
        }
        pool.shutdownNow()
        handles.forEach { runCatching { it.close() } }
    }

    companion object {
        const val DEFAULT_BLOCK_SIZE = 1 shl 20 // 1 MiB
        /**
         * On a Quest 3 over Wi-Fi 6E, reading like ExoPlayer: 4 in flight gave 93 Mbps, 8 gave 171,
         * 12 gave 215, 16 gave 270 (Regolith VR spike S3). 12 carries a 130 Mbps camera master
         * with 1.6x headroom for 12 MiB per open file.
         */
        const val DEFAULT_DEPTH = 12
    }
}
