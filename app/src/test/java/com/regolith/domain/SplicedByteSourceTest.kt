package com.regolith.domain

import com.regolith.domain.smb.SplicedByteSource
import com.regolith.testing.FakeSmbGateway.ByteArraySource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A file read with its middle cut out: the header, then everything from the cut on. */
class SplicedByteSourceTest {
    private val file = ByteArraySource(ByteArray(100) { it.toByte() })

    private fun readAll(source: SplicedByteSource): List<Int> {
        val out = ByteArray(source.size.toInt())
        var done = 0
        while (done < out.size) {
            val n = source.readAt(done.toLong(), out, done, out.size - done)
            if (n <= 0) break
            done += n
        }
        return out.take(done).map { it.toInt() and 0xFF }
    }

    @Test fun `the header, then the bytes from the cut on`() {
        val spliced = SplicedByteSource(file, headerEnd = 10, resumeAt = 60)
        assertEquals(50L, spliced.size)
        assertEquals((0 until 10) + (60 until 100), readAll(spliced))
    }

    @Test fun `a read never crosses the seam, and reads past the end say so`() {
        val spliced = SplicedByteSource(ByteArraySource(ByteArray(100) { it.toByte() }), headerEnd = 10, resumeAt = 60)
        val buffer = ByteArray(40)
        assertTrue(spliced.readAt(8, buffer, 0, 40) <= 2)
        assertEquals(-1, spliced.readAt(50, buffer, 0, 1))
    }

    @Test fun `no cut at all is the file as it is`() {
        assertEquals((0 until 100).toList(), readAll(SplicedByteSource(file, headerEnd = 30, resumeAt = 30)))
    }
}
