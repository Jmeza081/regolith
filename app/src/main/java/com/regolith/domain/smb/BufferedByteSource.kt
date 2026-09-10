package com.regolith.domain.smb

/**
 * Read-ahead over a [SeekableByteSource]. Fetches [blockSize]-byte blocks
 * from the underlying source and serves reads from memory, keeping the
 * [blockCount] most recently used blocks.
 *
 * Why: ExoPlayer's extractors read a container in tiny pieces, a few
 * bytes at a time for headers. Without this, every one of those became
 * an SMB round trip: measured at 2,000 reads averaging 30 bytes and 8 ms
 * each, i.e. ~4 KB/s, on a loopback share. One block fetch amortises
 * thousands of them.
 *
 * Why more than one block: frame extraction alternates between the sample
 * tables at the end of an MP4 and the frame bytes in the middle. With a
 * single block each of those reads evicted the other and refetched a
 * megabyte: measured at 23 reads, 62 KB, 4.5 s per frame. A handful of
 * smaller blocks keeps both regions resident. The player reads mostly
 * forward and keeps the one-block default.
 *
 * **Thread safe, and it has to be.** Both readers above it — ExoPlayer's
 * loader and `MediaMetadataRetriever`'s native decoder — call [readAt]
 * from threads this class does not own, and the retriever in particular
 * reads from more than one. An access-ordered `LinkedHashMap` mutates on
 * every *get*, so two concurrent reads could corrupt the map or hand back
 * a block that had already been evicted, and the symptom of that is a
 * frame decoded from the wrong bytes — on a share, never on a local file,
 * which is exactly the shape of bug that hides for months.
 */
class BufferedByteSource(
    private val source: SeekableByteSource,
    private val blockSize: Int = DEFAULT_BLOCK_SIZE,
    private val blockCount: Int = 1,
) : SeekableByteSource {
    override val size: Long get() = source.size

    private class Block(val start: Long, val data: ByteArray, val length: Int)

    /** Keyed by block start; access order so the least recently used block is evicted first. */
    private val blocks = object : LinkedHashMap<Long, Block>(4, 0.75f, /* accessOrder = */ true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Block>): Boolean = size > blockCount
    }

    override fun readAt(offset: Long, dst: ByteArray, dstOffset: Int, length: Int): Int {
        if (length == 0) return 0
        if (offset >= size) return -1
        val start = offset - (offset % blockSize) // align so sequential reads walk whole blocks
        // Only the cache is guarded; the copy out is not, because the block's
        // bytes never change once it is filled. Fetching inside the lock does
        // serialise two readers that miss at the same time — which is the
        // point: they would otherwise fetch the same block twice.
        val block = synchronized(blocks) { blocks[start] ?: fill(start) } ?: return -1
        val inBlock = (offset - start).toInt()
        if (inBlock >= block.length) return -1
        val n = minOf(length, block.length - inBlock)
        System.arraycopy(block.data, inBlock, dst, dstOffset, n)
        return n
    }

    /** Load the block starting at [start]. Returns null at end of file. */
    private fun fill(start: Long): Block? {
        val want = minOf(blockSize.toLong(), size - start).toInt()
        val data = ByteArray(want)
        var got = 0
        while (got < want) {
            val n = source.readAt(start + got, data, got, want - got)
            if (n <= 0) break
            got += n
        }
        if (got == 0) return null
        return Block(start, data, got).also { blocks[start] = it }
    }

    override fun close() {
        synchronized(blocks) { blocks.clear() }
        source.close()
    }

    companion object {
        const val DEFAULT_BLOCK_SIZE = 1 shl 20 // 1 MiB: ~130 ms of 60 Mbps 4K per fetch
    }
}
