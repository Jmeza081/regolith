package com.regolith.domain

import com.regolith.domain.smb.ReadAheadByteSource
import com.regolith.domain.smb.SeekableByteSource
import com.regolith.domain.smb.SmbFailure
import com.regolith.testing.FakeSmbGateway
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

class ReadAheadByteSourceTest {
    private val data = Random(11).nextBytes(20_000)

    /** Counts handles, and how many reads are on the "wire" at once (each read waits [delayMs]). */
    private inner class Server(private val delayMs: Long = 0, private val failAt: Long? = null) {
        val opened = AtomicInteger(0)
        val closed = AtomicInteger(0)
        val inFlight = AtomicInteger(0)
        val maxInFlight = AtomicInteger(0)
        var failuresLeft = 1

        fun open(): SeekableByteSource {
            opened.incrementAndGet()
            val inner = FakeSmbGateway.ByteArraySource(data) // short reads: at most 7 bytes each
            return object : SeekableByteSource {
                override val size get() = inner.size
                override fun readAt(offset: Long, dst: ByteArray, dstOffset: Int, length: Int): Int {
                    val now = inFlight.incrementAndGet()
                    maxInFlight.accumulateAndGet(now, ::maxOf)
                    try {
                        if (delayMs > 0) Thread.sleep(delayMs)
                        synchronized(this@Server) {
                            if (failAt != null && offset <= failAt && failAt < offset + length && failuresLeft > 0) {
                                failuresLeft--
                                throw SmbFailure.Unreachable("nas")
                            }
                        }
                        return inner.readAt(offset, dst, dstOffset, length)
                    } finally {
                        inFlight.decrementAndGet()
                    }
                }
                override fun close() { closed.incrementAndGet() }
            }
        }

        fun source(block: Int, depth: Int) = ReadAheadByteSource(open(), ::open, blockSize = block, depth = depth)
    }

    private fun readAll(src: SeekableByteSource, from: Long, count: Int, chunk: Int = 333): ByteArray {
        val out = ByteArray(count)
        var done = 0
        while (done < count) {
            val n = src.readAt(from + done, out, done, minOf(chunk, count - done))
            if (n < 0) break
            done += n
        }
        return out.copyOf(done)
    }

    @Test fun `sequential reads across block boundaries reproduce the file`() {
        Server().source(block = 1024, depth = 4).use { assertArrayEquals(data, readAll(it, 0, data.size)) }
    }

    @Test fun `random seeks read the right bytes`() {
        Server().source(block = 512, depth = 3).use { src ->
            for (offset in listOf(0L, 511L, 512L, 19_990L, 3L, 7_777L, 12_000L, 100L)) {
                val end = minOf(offset.toInt() + 40, data.size)
                assertArrayEquals(data.copyOfRange(offset.toInt(), end), readAll(src, offset, end - offset.toInt()))
            }
        }
    }

    @Test fun `end of file is signalled`() {
        Server().source(block = 4096, depth = 2).use { src ->
            assertEquals(-1, src.readAt(20_000, ByteArray(4), 0, 4))
            assertEquals(0, src.readAt(0, ByteArray(4), 0, 0))
        }
    }

    @Test fun `several blocks are fetched at once, on at most depth handles`() {
        val server = Server(delayMs = 2)
        server.source(block = 1024, depth = 4).use { assertArrayEquals(data, readAll(it, 0, data.size)) }
        assertTrue("reads overlapped: ${server.maxInFlight.get()}", server.maxInFlight.get() > 1)
        assertTrue("in flight ≤ depth: ${server.maxInFlight.get()}", server.maxInFlight.get() <= 4)
        assertTrue("handles ≤ depth: ${server.opened.get()}", server.opened.get() <= 4)
    }

    @Test fun `a failed fetch surfaces the SmbFailure itself, and a retry reads the block`() {
        Server(failAt = 5_000).source(block = 1024, depth = 2).use { src ->
            try {
                readAll(src, 5_000, 10)
                fail("expected the fetch to fail")
            } catch (e: SmbFailure.Unreachable) {
                // SmbDataSource maps this type to a playback error code, so it must not be wrapped.
            }
            assertArrayEquals(data.copyOfRange(5_000, 5_010), readAll(src, 5_000, 10))
        }
    }

    @Test fun `close closes every handle it opened`() {
        val server = Server(delayMs = 1)
        val src = server.source(block = 1024, depth = 4)
        readAll(src, 0, 8_000)
        src.close()
        assertEquals(server.opened.get(), server.closed.get())
    }
}
