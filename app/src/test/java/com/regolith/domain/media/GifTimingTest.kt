package com.regolith.domain.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream

/** How long a GIF plays through once, from its own frame delays (P20: a story waits for it). */
class GifTimingTest {

    /** A GIF of [delays] frames (in hundredths of a second), each a 1×1 picture; [globalTable] and [localTable] add colour tables. */
    private fun gif(vararg delays: Int, globalTable: Boolean = true, localTable: Boolean = false, comment: Boolean = false): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("GIF89a".toByteArray(Charsets.US_ASCII))
        // 1×1, a global table of 2 colours (size bits 0), when asked for.
        out.write(byteArrayOf(1, 0, 1, 0, (if (globalTable) 0x80 else 0x00).toByte(), 0, 0))
        if (globalTable) out.write(ByteArray(6))
        if (comment) out.write(byteArrayOf(0x21, 0xFE.toByte(), 3, 'h'.code.toByte(), 'i'.code.toByte(), '!'.code.toByte(), 0))
        for (delay in delays) {
            // Graphic Control Extension: block size 4, packed, delay (LE), transparent index, terminator.
            out.write(byteArrayOf(0x21, 0xF9.toByte(), 4, 0, (delay and 0xFF).toByte(), (delay shr 8).toByte(), 0, 0))
            // Image Descriptor: 0,0 1×1, then a local table when asked for.
            out.write(byteArrayOf(0x2C, 0, 0, 0, 0, 1, 0, 1, 0, (if (localTable) 0x80 else 0x00).toByte()))
            if (localTable) out.write(ByteArray(6))
            // LZW minimum code size, one data sub-block, the terminator.
            out.write(byteArrayOf(2, 2, 0x4C, 0x01, 0))
        }
        out.write(0x3B)
        return out.toByteArray()
    }

    @Test
    fun `the frames' delays add up`() {
        assertEquals(800L, GifTiming.durationMs(gif(50, 20, 10)))
    }

    @Test
    fun `a delay of nothing plays as a tenth of a second, as browsers play it`() {
        assertEquals(300L, GifTiming.durationMs(gif(0, 1, 10)))
    }

    @Test
    fun `colour tables and comments are stepped over`() {
        assertEquals(1_000L, GifTiming.durationMs(gif(40, 60, globalTable = false, localTable = true, comment = true)))
    }

    @Test
    fun `a still GIF has no length of its own`() {
        assertNull(GifTiming.durationMs(gif(50)))
    }

    @Test
    fun `what is not a GIF, or ends too soon, is not read`() {
        assertNull(GifTiming.durationMs("\u0089PNG\r\n\u001a\n........".toByteArray(Charsets.ISO_8859_1)))
        val whole = gif(50, 50)
        assertNull(GifTiming.durationMs(whole.copyOf(whole.size - 9)))
        assertNull(GifTiming.durationMs(ByteArray(3)))
    }
}
