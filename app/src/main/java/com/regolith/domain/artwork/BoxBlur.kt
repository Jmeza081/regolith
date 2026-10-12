package com.regolith.domain.artwork

/**
 * A soft blur for a small picture held as ARGB pixels (`0xAARRGGBB`, row by
 * row): [blur] runs a few box blurs, which together come close to a
 * Gaussian. Pure, so it is tested on plain arrays.
 *
 * Made for a deck's background, a copy of the front card shrunk to a
 * twentieth of the picture's size: at that size a few passes cost nothing,
 * and scaling the result back up smooths it the rest of the way. Web
 * analogy: CSS `filter: blur()`, done by hand because the deck is written
 * to a file, and Android's own blur (`RenderEffect`) only applies to what
 * is drawn on screen.
 */
object BoxBlur {
    /**
     * [pixels] blurred: [passes] box blurs [radius] pixels either way, along
     * the rows and then the columns. Past an edge, the edge pixel repeats,
     * so the border does not darken. Returns a new array; [pixels] is left
     * as it was.
     */
    fun blur(pixels: IntArray, width: Int, height: Int, radius: Int, passes: Int = 3): IntArray {
        require(pixels.size == width * height) { "${pixels.size} pixels is not $width × $height" }
        val current = pixels.copyOf()
        if (radius <= 0 || passes <= 0) return current
        val scratch = IntArray(pixels.size)
        repeat(passes) {
            pass(current, scratch, lines = height, length = width, stride = 1, step = width, radius = radius)
            pass(scratch, current, lines = width, length = height, stride = width, step = 1, radius = radius)
        }
        return current
    }

    /**
     * One box blur from [src] into [dst] along [lines] lines of [length]
     * pixels. Pixel `i` of line `n` is at `n * step + i * stride`, so the
     * same loop runs along rows (stride 1) and down columns (stride width).
     * A running sum, so each pixel costs the same whatever the radius.
     */
    private fun pass(src: IntArray, dst: IntArray, lines: Int, length: Int, stride: Int, step: Int, radius: Int) {
        val window = 2 * radius + 1
        val half = window / 2
        for (line in 0 until lines) {
            val base = line * step
            fun at(i: Int) = src[base + i.coerceIn(0, length - 1) * stride]
            var a = 0
            var r = 0
            var g = 0
            var b = 0
            for (i in -radius..radius) {
                val p = at(i)
                a += p ushr 24
                r += (p shr 16) and 0xFF
                g += (p shr 8) and 0xFF
                b += p and 0xFF
            }
            for (i in 0 until length) {
                dst[base + i * stride] =
                    ((a + half) / window shl 24) or ((r + half) / window shl 16) or ((g + half) / window shl 8) or ((b + half) / window)
                val leaving = at(i - radius)
                val entering = at(i + radius + 1)
                a += (entering ushr 24) - (leaving ushr 24)
                r += ((entering shr 16) and 0xFF) - ((leaving shr 16) and 0xFF)
                g += ((entering shr 8) and 0xFF) - ((leaving shr 8) and 0xFF)
                b += (entering and 0xFF) - (leaving and 0xFF)
            }
        }
    }
}
