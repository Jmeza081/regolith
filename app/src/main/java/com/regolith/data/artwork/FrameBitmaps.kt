package com.regolith.data.artwork

import android.graphics.Bitmap
import androidx.core.graphics.scale
import com.regolith.domain.artwork.FrameLook

/**
 * This frame in ordinary memory: a software canvas cannot draw a hardware
 * bitmap, and its pixels cannot be read. Media3's frames may come either
 * way; a hardware one is copied, and given back.
 */
internal fun Bitmap.software(): Bitmap {
    if (config != Bitmap.Config.HARDWARE) return this
    val copy = copy(Bitmap.Config.ARGB_8888, false)
    recycle()
    return copy
}

/**
 * This bitmap scaled to [width] × [height], halved a step at a time first:
 * one big scaling step reads only a few source pixels per result pixel, so a
 * band half black and half picture could come out all black; halving
 * averages every pixel in. A new bitmap, unless this one is that size already.
 */
internal fun Bitmap.averagedTo(width: Int, height: Int): Bitmap {
    var current = this
    while (current.width >= width * 2 && current.height >= height * 2) {
        val half = current.scale(current.width / 2, current.height / 2)
        if (current !== this) current.recycle()
        current = half
    }
    if (current.width == width && current.height == height) return current
    val out = current.scale(width, height)
    if (current !== this) current.recycle()
    return out
}

/** Its brightness on a [width] × [height] grid, for [FrameLook]: averaged down to it first. */
internal fun Bitmap.lumaGrid(width: Int = LUMA_GRID_WIDTH, height: Int = LUMA_GRID_HEIGHT): IntArray {
    val small = averagedTo(width, height)
    val pixels = IntArray(width * height)
    small.getPixels(pixels, 0, width, 0, 0, width, height)
    if (small !== this) small.recycle()
    return IntArray(pixels.size) { i ->
        val c = pixels[i]
        FrameLook.luma((c shr 16) and 0xFF, (c shr 8) and 0xFF, c and 0xFF)
    }
}

/**
 * This frame without the black bars baked into its video ([FrameLook.picture]),
 * a new bitmap; or this one when it has none.
 */
internal fun Bitmap.withoutBars(): Bitmap {
    val gridWidth = BAR_GRID
    val gridHeight = (BAR_GRID * height / width.toFloat()).toInt().coerceIn(8, 4 * BAR_GRID)
    val box = FrameLook.picture(lumaGrid(gridWidth, gridHeight), gridWidth, gridHeight) ?: return this
    // A cell in from each edge found: the cell on the seam is part bar.
    val left = ((box[0] + if (box[0] > 0) 1 else 0) * width / gridWidth)
    val top = ((box[1] + if (box[1] > 0) 1 else 0) * height / gridHeight)
    val right = ((box[2] - if (box[2] < gridWidth) 1 else 0) * width / gridWidth)
    val bottom = ((box[3] - if (box[3] < gridHeight) 1 else 0) * height / gridHeight)
    if (right - left < width / 3 || bottom - top < height / 3) return this
    return Bitmap.createBitmap(this, left, top, right - left, bottom - top)
}

private const val LUMA_GRID_WIDTH = 32
private const val LUMA_GRID_HEIGHT = 18

/** Fine enough to find a bar a twentieth of the frame deep. */
private const val BAR_GRID = 64
