package com.regolith.domain.smb

/**
 * A file read with a stretch of its middle cut out: its first [headerEnd]
 * bytes, then [inserted], then everything from [resumeAt] on, as one
 * seamless run of bytes.
 *
 * What it is for: a video with no index cannot be seeked, so a decoder
 * reading it from the top only ever reaches its opening frames. Its header
 * (what the tracks are and how they are coded) followed by the frames from a
 * key frame in the middle is a valid file of its own, one that starts in the
 * middle ([com.regolith.domain.media.UnindexedVideo]); [inserted] is the
 * little the container needs in between (a Matroska cluster's start). Web
 * analogy: an HLS player fetching the init segment and then one media
 * segment, rather than the whole stream.
 */
class SplicedByteSource(
    private val source: SeekableByteSource,
    private val headerEnd: Long,
    private val resumeAt: Long,
    private val inserted: ByteArray = ByteArray(0),
) : SeekableByteSource {
    init {
        require(headerEnd in 0..resumeAt) { "the header ($headerEnd) must end before the cut ($resumeAt)" }
    }

    private val tailStart = headerEnd + inserted.size

    override val size: Long = tailStart + (source.size - resumeAt).coerceAtLeast(0)

    override fun readAt(offset: Long, dst: ByteArray, dstOffset: Int, length: Int): Int {
        if (offset >= size) return -1
        // Never across a seam in one read: the caller loops, and its next read starts after it.
        return when {
            offset < headerEnd -> source.readAt(offset, dst, dstOffset, minOf(length.toLong(), headerEnd - offset).toInt())
            offset < tailStart -> {
                val from = (offset - headerEnd).toInt()
                val n = minOf(length, inserted.size - from)
                System.arraycopy(inserted, from, dst, dstOffset, n)
                n
            }
            else -> source.readAt(resumeAt + (offset - tailStart), dst, dstOffset, length)
        }
    }

    override fun close() = source.close()
}
