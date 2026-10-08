package com.regolith.ui.player

import android.graphics.Bitmap
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.get
import kotlin.math.roundToInt

/*
 * Reading the film off its video surface: the ambient light's samples
 * (AmbientSampler.kt), and the frame the player and the mini player hand each
 * other as the film passes between them (MiniPlayerHandoff.frame). Only a
 * TextureView can be read back; a SurfaceView goes straight to the compositor.
 */

/**
 * The film's frame as it is on screen now, at most [FRAME_LONG_SIDE] px on its
 * long side, or null when no surface has one to give.
 *
 * Read from the surface's own buffer rather than the screen, so it is the
 * whole frame in the film's shape, whatever the box around it crops or
 * letterboxes, and nothing drawn over it comes along. The first surface with a
 * frame on it wins: the player and the mini player are only both on screen for
 * the moment one hands the film to the other, when the new one is still empty.
 */
internal fun View.videoFrame(): ImageBitmap? {
    for (texture in textureViews()) {
        if (!texture.isAvailable || texture.width <= 0 || texture.height <= 0) continue
        val scale = minOf(1f, FRAME_LONG_SIDE / maxOf(texture.width, texture.height).toFloat())
        val width = (texture.width * scale).roundToInt().coerceAtLeast(1)
        val height = (texture.height * scale).roundToInt().coerceAtLeast(1)
        val frame = runCatching { texture.getBitmap(width, height) }.getOrNull() ?: continue
        if (!frame.isBlank()) return frame.asImageBitmap()
    }
    return null
}

/**
 * Big enough for the mini player at its largest, and for the first moments of
 * the player's picture growing out of it, after which its own frames take
 * over; a 2 MB read, made once per hand-over.
 */
private const val FRAME_LONG_SIDE = 960

/**
 * The video surface, found by walking down from the Compose root.
 *
 * Media3's `ContentFrame` owns the view and does not hand it out, and its
 * sizing and content-scaling are worth more than a handle would be — so the
 * view is located instead of constructed. The player screen has one
 * TextureView (the mini player's is beside it only for the moment of a
 * hand-over), so there is nothing to disambiguate, and a null (a Media3 that
 * stops using one) costs the static backdrop, not a crash.
 */
internal fun View.findTextureView(): TextureView? = textureViews().firstOrNull()

/** Every TextureView under this one, in drawing order. */
private fun View.textureViews(): Sequence<TextureView> = when (this) {
    is TextureView -> sequenceOf(this)
    is ViewGroup -> (0 until childCount).asSequence().flatMap { getChildAt(it).textureViews() }
    else -> emptySequence()
}

/**
 * True while the surface has drawn nothing yet. A TextureView with no frame
 * on it reads back fully transparent, and letting that through would blink
 * the light off every time a file loads, or hand over nothing for a picture.
 * A genuinely black shot is opaque and passes, which is right — the room
 * should go dark with the film.
 */
internal fun Bitmap.isBlank(): Boolean {
    // Four corners and the middle: enough to tell "nothing drawn" from "a
    // dark shot", and cheaper than reading 576 pixels on every sample.
    val probes = intArrayOf(
        this[0, 0], this[width - 1, 0], this[0, height - 1],
        this[width - 1, height - 1], this[width / 2, height / 2],
    )
    return probes.all { (it ushr 24) == 0 }
}
