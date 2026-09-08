package com.regolith.domain.smb

/**
 * Read-ahead over a [SeekableByteSource]. Fetches [blockSize]-byte blocks
 * from the underlying source and serves reads from memory.
 *
 * Why: ExoPlayer's extractors read a container in tiny pieces, a few
 * bytes at a time for headers. Without this, every one of those became
 * an SMB round trip: measured at 2,000 reads averaging 30 bytes and 8 ms
 * each, i.e. ~4 KB/s, on a loopback share. One block fetch amortises
 * thousands of them. A seek outside the current block fetches a new one.
 */
class BufferedByteSource(
    private val source: SeekableByteSource,
    private val blockSize: Int = DEFAULT_BLOCK_SIZE,
) : SeekableByteSource {
    override val size: Long get() = source.size

    private val block = ByteArray(blockSize)
    private var blockStart = -1L
    private var blockLength = 0

    override fun readAt(offset: Long, dst: ByteArray, dstOffset: Int, length: Int): Int {
        if (length == 0) return 0
        if (offset >= size) return -1
        if (blockStart < 0 || offset < blockStart || offset >= blockStart + blockLength) {
            if (!fill(offset)) return -1
        }
        val inBlock = (offset - blockStart).toInt()
        val n = minOf(length, blockLength - inBlock)
        System.arraycopy(block, inBlock, dst, dstOffset, n)
        return n
    }

    /** Load the block containing [offset]. Returns false at end of file. */
    private fun fill(offset: Long): Boolean {
        val start = offset - (offset % blockSize) // align so sequential reads walk whole blocks
        val want = minOf(blockSize.toLong(), size - start).toInt()
        var got = 0
        while (got < want) {
            val n = source.readAt(start + got, block, got, want - got)
            if (n <= 0) break
            got += n
        }
        blockStart = start
        blockLength = got
        return got > 0 && offset < start + got
    }

    override fun close() = source.close()

    companion object {
        const val DEFAULT_BLOCK_SIZE = 1 shl 20 // 1 MiB: ~130 ms of 60 Mbps 4K per fetch
    }
}
