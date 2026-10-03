package com.regolith.ui.player

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
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
import com.regolith.domain.playback.AmbientLight
import com.regolith.domain.playback.BleedZone
import com.regolith.domain.playback.ColorBleed
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.coroutines.coroutineContext
import kotlin.math.abs

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
 *
 * Both live lights read the same surface on the same clock; they differ in
 * what they keep. Mirror keeps the picture (a blended 32×18); Color bleed
 * keeps one colour per zone along its edges ([ColorBleed]).
 */

/** What a live light has to draw with this time round. */
sealed interface AmbientSample {
    /** Mirror: the picture on screen, shrunk to 32×18 and blended into the last sample. */
    class Frame(val bitmap: Bitmap) : AmbientSample

    /**
     * Color bleed: the light each zone shines (ARGB, in [zones] order) and
     * where the picture sits in the window, in px — the lights are hung on
     * its edges, so the light has to know where those are.
     */
    class Edges(val colors: IntArray, val zones: List<BleedZone>, val picture: Rect) : AmbientSample
}

/** The grid the light is built from. Tiny on purpose: see [rememberAmbientLight]. */
private const val SAMPLE_W = 32
private const val SAMPLE_H = 18

/**
 * Color bleed's read of the same surface: finer than Mirror's, because each
 * zone along the top of a scope film is only an eighth of the width and a
 * fifth of the height, and 32×18 would give it a dozen pixels to vote with.
 * Still a 9 KB read.
 */
private const val EDGE_W = 64
private const val EDGE_H = 36

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
 * For Mirror, a 32×18 bitmap, not a picture: it is drawn scaled up to the
 * whole screen and blurred, so anything more is detail that gets thrown
 * away. It also means each sample is a 2 KB upload rather than a full frame,
 * which is what makes eight a second affordable.
 *
 * For Color bleed, two dozen colours and the picture's place on screen —
 * and only when one of them changed, so a paused film emits nothing and
 * the light behind it stops redrawing.
 *
 * @param light the setting, and the only thing that stops the loop.
 * @param key changes when the film does, so the light starts clean.
 */
@Composable
fun rememberAmbientLight(light: AmbientLight, key: Any?): State<AmbientSample?> {
    val root = LocalView.current
    val out = remember { mutableStateOf<AmbientSample?>(null) }
    // Buffers reused for the life of the player: allocating per sample would
    // be a stream of bitmaps for the collector to clean up.
    val scratch = remember { createBitmap(SAMPLE_W, SAMPLE_H) }
    val blend = remember { createBitmap(SAMPLE_W, SAMPLE_H) }
    val edges = remember { createBitmap(EDGE_W, EDGE_H) }
    val pixels = remember { IntArray(EDGE_W * EDGE_H) }

    LaunchedEffect(light, key) {
        out.value = null
        when (light) {
            AmbientLight.OFF -> Unit
            AmbientLight.MIRROR -> sampleMirror(root, scratch, blend) { out.value = it }
            AmbientLight.COLOR_BLEED -> sampleEdges(root, edges, pixels) { out.value = it }
        }
    }
    return out
}

/** Mirror: blend each new 32×18 into the last, eight times a second. */
private suspend fun sampleMirror(root: View, scratch: Bitmap, blend: Bitmap, emit: (AmbientSample) -> Unit) {
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
            emit(AmbientSample.Frame(blend.copy(Bitmap.Config.ARGB_8888, false)))
        }
        delay(SAMPLE_PERIOD_MS)
    }
}

/**
 * Color bleed: read a 64×36 of the picture, and give each zone around it
 * one colour. Nothing more here: each zone eases to its colour, and the
 * zones blend into one another, where they are drawn ([ColorBleedLight]),
 * every frame — smoother than eight steps a second, and two dozen numbers
 * to hand over rather than a bitmap.
 */
private suspend fun sampleEdges(root: View, edges: Bitmap, pixels: IntArray, emit: (AmbientSample) -> Unit) {
    val at = IntArray(2)
    var zones: List<BleedZone> = emptyList()
    var zonesAspect = 0f
    var last: AmbientSample.Edges? = null
    while (coroutineContext.isActive) {
        val texture = root.findTextureView()
        val got = texture != null && texture.isAvailable && texture.width > 0 && texture.height > 0 &&
            runCatching { texture.getBitmap(edges) != null }.getOrDefault(false)
        if (got && !edges.isBlank()) {
            // The zones follow the picture's shape on screen: a scope film
            // hangs more lights along the top than a 4:3 one does.
            val aspect = texture.width / texture.height.toFloat()
            if (abs(aspect - zonesAspect) > ASPECT_SLACK) {
                zones = ColorBleed.zones(aspect = aspect)
                zonesAspect = aspect
            }
            edges.getPixels(pixels, 0, EDGE_W, 0, 0, EDGE_W, EDGE_H)
            val colors = ColorBleed.colors(pixels, EDGE_W, EDGE_H, zones)
            texture.getLocationInWindow(at)
            val picture = Rect(at[0], at[1], at[0] + texture.width, at[1] + texture.height)
            val before = last
            if (before == null || before.zones !== zones || before.picture != picture || !before.colors.contentEquals(colors)) {
                last = AmbientSample.Edges(colors, zones, picture).also(emit)
            }
        }
        delay(SAMPLE_PERIOD_MS)
    }
}

/** How far the picture's shape may drift before its zones are laid out again. */
private const val ASPECT_SLACK = 0.01f

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
