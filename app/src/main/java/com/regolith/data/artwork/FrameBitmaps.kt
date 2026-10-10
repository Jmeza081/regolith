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

/** Its brightness on a small grid, for [FrameLook.blank]: shrunk first, which also averages it. */
internal fun Bitmap.lumaGrid(): IntArray {
    val small = scale(LUMA_GRID_WIDTH, LUMA_GRID_HEIGHT)
    val pixels = IntArray(LUMA_GRID_WIDTH * LUMA_GRID_HEIGHT)
    small.getPixels(pixels, 0, LUMA_GRID_WIDTH, 0, 0, LUMA_GRID_WIDTH, LUMA_GRID_HEIGHT)
    if (small !== this) small.recycle()
    return IntArray(pixels.size) { i ->
        val c = pixels[i]
        FrameLook.luma((c shr 16) and 0xFF, (c shr 8) and 0xFF, c and 0xFF)
    }
}

private const val LUMA_GRID_WIDTH = 32
private const val LUMA_GRID_HEIGHT = 18
