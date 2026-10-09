package com.regolith.domain.media

/**
 * How long a GIF takes to play through once, read from its own frame delays
 * — for a story, where a moving GIF stays until it has played (Q16). Pure:
 * the format is a header, then blocks, and the delays sit in each frame's
 * Graphic Control Extension, so no decoder is needed to know the length.
 */
object GifTiming {

    /**
     * The total of [bytes]' frame delays in milliseconds, or null when it is
     * not a GIF, holds one frame (a still), or ends before its frames do.
     * A delay of 10 ms or less is played as 100 ms, as browsers and Android's
     * own decoder play it.
     */
    fun durationMs(bytes: ByteArray): Long? {
        if (bytes.size < HEADER + SCREEN) return null
        val signature = String(bytes, 0, HEADER, Charsets.US_ASCII)
        if (signature != "GIF87a" && signature != "GIF89a") return null
        var at = HEADER
        val screenPacked = bytes[at + 4].toInt() and 0xFF
        at += SCREEN
        if (screenPacked and 0x80 != 0) at += colorTable(screenPacked)
        var frames = 0
        var total = 0L
        var delay = 0
        while (at < bytes.size) {
            when (bytes[at].toInt() and 0xFF) {
                EXTENSION -> {
                    if (at + 1 >= bytes.size) return null
                    val label = bytes[at + 1].toInt() and 0xFF
                    at += 2
                    if (label == GRAPHIC_CONTROL && at + 4 < bytes.size && (bytes[at].toInt() and 0xFF) == 4) {
                        delay = (bytes[at + 2].toInt() and 0xFF) or ((bytes[at + 3].toInt() and 0xFF) shl 8)
                    }
                    at = skipSubBlocks(bytes, at) ?: return null
                }
                IMAGE -> {
                    if (at + 10 > bytes.size) return null
                    val packed = bytes[at + 9].toInt() and 0xFF
                    at += 10
                    if (packed and 0x80 != 0) at += colorTable(packed)
                    // The LZW minimum code size, then the picture's data.
                    at = skipSubBlocks(bytes, at + 1) ?: return null
                    frames++
                    total += if (delay * 10 <= SHORTEST_MS) DEFAULT_MS else delay * 10L
                    delay = 0
                }
                TRAILER -> break
                else -> return null
            }
        }
        return total.takeIf { frames > 1 }
    }

    /** A color table's length in bytes: three a colour, two to the (n + 1) of them. */
    private fun colorTable(packed: Int): Int = 3 * (1 shl ((packed and 0x07) + 1))

    /** Past a run of sub-blocks (a length byte, that many bytes, until a zero); null when the file ends first. */
    private fun skipSubBlocks(bytes: ByteArray, from: Int): Int? {
        var at = from
        while (true) {
            if (at >= bytes.size) return null
            val length = bytes[at].toInt() and 0xFF
            at += 1
            if (length == 0) return at
            at += length
        }
    }

    private const val HEADER = 6
    private const val SCREEN = 7
    private const val EXTENSION = 0x21
    private const val GRAPHIC_CONTROL = 0xF9
    private const val IMAGE = 0x2C
    private const val TRAILER = 0x3B
    private const val SHORTEST_MS = 10
    private const val DEFAULT_MS = 100L
}
