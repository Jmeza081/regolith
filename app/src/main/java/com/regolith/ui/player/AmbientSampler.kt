package com.regolith.ui.player

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import androidx.core.graphics.createBitmap
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.coroutines.coroutineContext

/**
 * The ambient light's source: the picture that is actually on screen,
 * sampled straight off the video surface several times a second.
 *
 * Why not the scrub cache, which already holds frames: those are key-frame
 * seeks over the share, quantised to ten seconds. Ten seconds is fine for a
 * seek preview and far too slow for a light that is meant to move with the
 * film. Reading the surface costs no network at all and can run at whatever
 * rate we choose.
 *
 * The cost, and the reason [com.regolith.data.prefs.AppPreferences.ambientLight]
 * exists: a surface can only be read back if it is a `TextureView`, and a
 * TextureView is a real step down from a `SurfaceView` — the video goes
 * through the view hierarchy's own drawing instead of straight to the
 * compositor. With the light off the player keeps its SurfaceView and none
 * of this runs.
 */

/** The grid the light is built from. Tiny on purpose: see [rememberAmbientLight]. */
private const val SAMPLE_W = 32
private const val SAMPLE_H = 18

/**
 * How often the surface is read. Eight times a second is under the rate at
 * which a blurred wash reads as "live" to the eye, and 7.5× less work than
 * doing it per frame.
 */
private const val SAMPLE_PERIOD_MS = 125L

/**
 * How much of each new sample is mixed into the light, per sample.
 *
 * The smoothing is done HERE, in 576 pixels, rather than by cross-fading
 * two full-screen layers: a cross-fade animates alpha every frame, which
 * dirties the blurred layer sixty times a second and makes the blur the
 * most expensive thing on the screen. Blending in the sample instead leaves
 * exactly [SAMPLE_PERIOD_MS] between redraws, and costs a 32×18 draw.
 *
 * 0.3 at 8 Hz settles a cut in about half a second: fast enough to belong
 * to the film, slow enough that a single bright frame does not flash the
 * room.
 */
private const val SAMPLE_MIX = 0.30f

/**
 * The live ambient light, or null when it is off, has no surface to read,
 * or has not yet seen a frame.
 *
 * Returns a 32×18 bitmap, not a picture: it is drawn scaled up to the whole
 * screen and blurred, so anything more is detail that gets thrown away. It
 * also means each sample is a 2 KB upload rather than a full frame, which
 * is what makes eight a second affordable.
 *
 * @param enabled the setting, and the only thing that stops the loop.
 * @param key changes when the film does, so the light starts clean.
 */
@Composable
fun rememberAmbientLight(enabled: Boolean, key: Any?): State<Bitmap?> {
    val root = LocalView.current
    val out = remember { mutableStateOf<Bitmap?>(null) }
    // Two buffers, reused for the life of the player: one to read into, one
    // holding the blend. Allocating a pair per sample would be 16 bitmaps a
    // second for the collector to clean up.
    val scratch = remember { createBitmap(SAMPLE_W, SAMPLE_H) }
    val blend = remember { createBitmap(SAMPLE_W, SAMPLE_H) }

    LaunchedEffect(enabled, key) {
        out.value = null
        if (!enabled) return@LaunchedEffect
        val canvas = Canvas(blend)
        val paint = Paint()
        var seeded = false
        while (coroutineContext.isActive) {
            val texture = root.findTextureView()
            val got = texture != null && texture.isAvailable &&
                runCatching { texture.getBitmap(scratch) != null }.getOrDefault(false)
            if (got && !scratch.isBlank()) {
                // Fully opaque for the first sample, so the light arrives at
                // the picture's own colour rather than fading up out of black.
                paint.alpha = if (seeded) (SAMPLE_MIX * 255).toInt() else 255
                canvas.drawBitmap(scratch, 0f, 0f, paint)
                seeded = true
                // A new bitmap per emission, not the one being drawn: Compose
                // may be reading the last one on the render thread while this
                // one is written, and a torn frame is a visible flash.
                out.value = blend.copy(Bitmap.Config.ARGB_8888, false)
            }
            delay(SAMPLE_PERIOD_MS)
        }
    }
    return out
}

/**
 * True while the surface has drawn nothing yet. A TextureView with no frame
 * on it reads back fully transparent, and letting that through would blink
 * the light off every time a file loads. A genuinely black shot is opaque
 * and passes, which is right — the room should go dark with the film.
 */
private fun Bitmap.isBlank(): Boolean {
    // Four corners and the middle: enough to tell "nothing drawn" from "a
    // dark shot", and cheaper than reading 576 pixels on every sample.
    val probes = intArrayOf(
        getPixel(0, 0), getPixel(width - 1, 0), getPixel(0, height - 1),
        getPixel(width - 1, height - 1), getPixel(width / 2, height / 2),
    )
    return probes.all { (it ushr 24) == 0 }
}

/**
 * The player's video surface, found by walking down from the Compose root.
 *
 * Media3's `ContentFrame` owns the view and does not hand it out, and its
 * sizing and content-scaling are worth more than a handle would be — so the
 * view is located instead of constructed. The player screen has exactly one
 * TextureView, so there is nothing to disambiguate, and a null (a Media3
 * that stops using one) costs the static backdrop, not a crash.
 */
private fun View.findTextureView(): TextureView? = when {
    this is TextureView -> this
    this is ViewGroup -> (0 until childCount).firstNotNullOfOrNull { getChildAt(it).findTextureView() }
    else -> null
}
