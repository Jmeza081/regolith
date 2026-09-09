package com.regolith.domain

import com.regolith.domain.smb.BufferedByteSource
import com.regolith.testing.FakeSmbGateway
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import com.regolith.domain.smb.SeekableByteSource
import org.junit.Test
import kotlin.random.Random

class BufferedByteSourceTest {
    private val data = Random(7).nextBytes(10_000)

    /** The fake returns at most 7 bytes per read, so filling a block loops many times. */
    private fun source(block: Int) = BufferedByteSource(FakeSmbGateway.ByteArraySource(data), blockSize = block)

    private fun readAll(src: BufferedByteSource, from: Long, count: Int): ByteArray {
        val out = ByteArray(count)
        var done = 0
        while (done < count) {
            val n = src.readAt(from + done, out, done, count - done)
            if (n < 0) break
            done += n
        }
        return out.copyOf(done)
    }

    @Test fun `sequential reads across block boundaries reproduce the file`() {
        assertArrayEquals(data, readAll(source(block = 1024), 0, data.size))
    }

    @Test fun `random seeks read the right bytes`() {
        val src = source(block = 512)
        for (offset in listOf(0L, 511L, 512L, 9_990L, 3L, 7_777L)) {
            val n = 16
            assertArrayEquals(data.copyOfRange(offset.toInt(), minOf(offset.toInt() + n, data.size)), readAll(src, offset, n))
        }
    }

    @Test fun `end of file is signalled`() {
        val src = source(block = 4096)
        assertEquals(-1, src.readAt(10_000, ByteArray(4), 0, 4))
        assertEquals(0, src.readAt(0, ByteArray(4), 0, 0))
    }

    @Test fun `several blocks stay resident so alternating regions do not refetch`() {
        val counting = object : SeekableByteSource {
            var fetches = 0
            val inner = FakeSmbGateway.ByteArraySource(data)
            override val size get() = inner.size
            override fun readAt(offset: Long, dst: ByteArray, dstOffset: Int, length: Int): Int { fetches++; return inner.readAt(offset, dst, dstOffset, length) }
            override fun close() = inner.close()
        }
        val src = BufferedByteSource(counting, blockSize = 1024, blockCount = 2)
        // Head and tail of the file, like an MP4's frame bytes and its sample tables.
        for (round in 0 until 5) {
            assertArrayEquals(data.copyOfRange(10, 26), readAll(src, 10, 16))
            assertArrayEquals(data.copyOfRange(9_000, 9_016), readAll(src, 9_000, 16))
        }
        val afterTwoBlocks = counting.fetches
        readAll(src, 10, 16); readAll(src, 9_000, 16)
        assertEquals("both blocks served from memory", afterTwoBlocks, counting.fetches)
        // A third region evicts the least recently used one (offset 10's block).
        readAll(src, 5_000, 16)
        val afterThird = counting.fetches
        readAll(src, 9_000, 16)
        assertEquals(afterThird, counting.fetches)
        readAll(src, 10, 16)
        assertTrue(counting.fetches > afterThird)
    }
}
