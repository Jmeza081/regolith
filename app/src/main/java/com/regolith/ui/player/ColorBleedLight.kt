package com.regolith.ui.player

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.ComposeShader
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.graphics.createBitmap
import com.regolith.domain.playback.BleedStrips
import com.regolith.domain.playback.BleedZone
import com.regolith.domain.playback.ColorBleed
import com.regolith.ui.theme.RegolithTheme
import kotlin.math.roundToInt

/**
 * Color bleed's light: a soft glow from the picture's edges out into the
 * space around it, the way an LED strip behind a TV lights the wall. It is
 * drawn behind the picture, so the only light anyone sees is the light that
 * leaves its edges.
 *
 * Each zone eases to its newest colour every frame ([ColorBleed.ease]),
 * which is what keeps one bright frame from flashing the room. The easing
 * stops once every zone has arrived, so a paused film costs nothing.
 *
 * The zones are drawn as one continuous glow ([BleedPainter]), never as a
 * pool of light each. Separate pools overlapped into brighter columns, each
 * its own colour with nothing blending them, and in the tall bars around a
 * scope film those read as pillars.
 *
 * Cheap on purpose. Mirror re-blurs a full-screen layer on every sample;
 * this fills eight shapes around the picture, never where the picture is,
 * with no blur pass at all.
 *
 * @param sample the newest colours and where the picture is, or null before
 *   the first one (nothing is drawn).
 * @param spill true where the light fills letterbox bars around a full-screen
 *   picture, false behind the windowed player's page, where it falls away to
 *   the app's ground so the words below the picture stay readable.
 */
@Composable
fun ColorBleedLight(sample: AmbientSample.Edges?, modifier: Modifier = Modifier, spill: Boolean = false) {
    // Eased light, r g b per zone (ColorBleed.seed), mutated in place frame
    // by frame; [tick] is what tells the canvas a frame has moved it.
    var current by remember { mutableStateOf<FloatArray?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    var origin by remember { mutableStateOf(Offset.Zero) }

    LaunchedEffect(sample) {
        val s = sample ?: return@LaunchedEffect
        val eased = current
        if (eased == null || eased.size != s.colors.size * 3) {
            // The first light, or a picture of a new shape: arrive at the
            // picture's own colour rather than fading up out of black.
            current = ColorBleed.seed(s.colors)
            tick++
            return@LaunchedEffect
        }
        var last = withFrameNanos { it }
        do {
            val now = withFrameNanos { it }
            val moving = ColorBleed.ease(eased, s.colors, (now - last) / 1_000_000f, RESPONSE_MS)
            last = now
            tick++
        } while (moving)
    }

    val painter = remember { BleedPainter() }
    val ground = RegolithTheme.colors.ground
    Box(modifier.onGloballyPositioned { origin = it.positionInWindow() }) {
        Canvas(Modifier.fillMaxSize().testTag("player_ambient_bleed")) {
            tick
            val s = sample ?: return@Canvas
            val eased = current ?: return@Canvas
            if (eased.size != s.colors.size * 3) return@Canvas
            val strength = if (spill) SPILL_STRENGTH else PAGE_STRENGTH
            drawIntoCanvas { canvas ->
                painter.draw(canvas.nativeCanvas, s, origin, eased, REACH.toPx(), TUCK.toPx(), strength)
            }
        }
        if (!spill) {
            // Behind the windowed page the light belongs to the picture: it
            // fades to the app's own ground on the way down, as Mirror's does.
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Transparent, 0.5f to PAGE_SCRIM, 1f to ground)))
        }
    }
}

/**
 * Draws Color bleed's light as one continuous glow: a band along each edge
 * of the picture, its colour running smoothly from zone to zone
 * ([BleedStrips]) and fading on one curve ([falloff]) as it leaves the
 * picture, plus a quarter-disc at each corner in the colour the two edges
 * share there. The eight shapes meet edge to edge without overlapping, so an
 * edge of one colour is one even band of light.
 *
 * Each shape's shader is two shaders multiplied (a [ComposeShader]; web
 * analogy: a gradient seen through a CSS mask). One is the edge's strip of
 * colours, a bitmap one pixel thick stretched along it; the other is a white
 * gradient whose alpha carries the fade. The bitmaps are rewritten in place
 * every frame and the shaders rebuilt only when the picture moves, so a frame
 * allocates nothing — fresh shaders 60 times a second would be garbage for
 * the collector.
 */
private class BleedPainter {
    private val samples = BleedStrips.SAMPLES

    // The strips as pixels: lying along the top and bottom, standing up the sides.
    private val topPixels = createBitmap(samples, 1)
    private val bottomPixels = createBitmap(samples, 1)
    private val leftPixels = createBitmap(1, samples)
    private val rightPixels = createBitmap(1, samples)

    private var zones: List<BleedZone> = emptyList()
    private var strips: BleedStrips? = null
    private var colors = IntArray(0)

    private val picture = RectF()
    private val laidOut = RectF()
    private var laidOutReach = 0f

    /** One per shape, indexed by [TOP]…[BOTTOM_LEFT]; built by [layOut]. */
    private val shaders = arrayOfNulls<Shader>(SHAPES)

    private val paint = Paint().apply {
        // Without dither a fade to black on an OLED steps visibly from one 8-bit shade to the next.
        isDither = true
    }

    fun draw(
        canvas: android.graphics.Canvas,
        sample: AmbientSample.Edges,
        origin: Offset,
        eased: FloatArray,
        reach: Float,
        tuck: Float,
        strength: Float,
    ) {
        // The picture in this box's own coordinates: the sampler measured it
        // in the window's, because the video is somebody else's View.
        val r = sample.picture
        picture.set(r.left - origin.x, r.top - origin.y, r.right - origin.x, r.bottom - origin.y)
        if (picture.width() <= 0f || picture.height() <= 0f) return

        if (sample.zones != zones) {
            zones = sample.zones
            strips = BleedStrips(zones, samples)
            colors = IntArray(zones.size)
        }
        val strips = strips ?: return
        for (i in colors.indices) colors[i] = ColorBleed.colorAt(eased, i)
        strips.fill(colors)
        topPixels.setPixels(strips.top, 0, samples, 0, 0, samples, 1)
        bottomPixels.setPixels(strips.bottom, 0, samples, 0, 0, samples, 1)
        leftPixels.setPixels(strips.left, 0, 1, 0, 0, 1, samples)
        rightPixels.setPixels(strips.right, 0, 1, 0, 0, 1, samples)

        if (picture != laidOut || reach != laidOutReach) layOut(reach)

        paint.alpha = (strength * 255f).roundToInt()
        val p = picture
        // The bands tuck under the picture by a hair, out of sight, so no dark
        // line can open between the two; the corners only touch it at a point.
        canvas.shape(TOP, p.left, p.top - reach, p.right, p.top + tuck)
        canvas.shape(RIGHT, p.right - tuck, p.top, p.right + reach, p.bottom)
        canvas.shape(BOTTOM, p.left, p.bottom - tuck, p.right, p.bottom + reach)
        canvas.shape(LEFT, p.left - reach, p.top, p.left + tuck, p.bottom)
        canvas.shape(TOP_LEFT, p.left - reach, p.top - reach, p.left, p.top)
        canvas.shape(TOP_RIGHT, p.right, p.top - reach, p.right + reach, p.top)
        canvas.shape(BOTTOM_RIGHT, p.right, p.bottom, p.right + reach, p.bottom + reach)
        canvas.shape(BOTTOM_LEFT, p.left - reach, p.bottom, p.left, p.bottom + reach)
    }

    private fun android.graphics.Canvas.shape(which: Int, left: Float, top: Float, right: Float, bottom: Float) {
        paint.shader = shaders[which]
        drawRect(left, top, right, bottom, paint)
    }

    /** Hangs every shader on the picture where it is now: only when it has moved or changed size. */
    private fun layOut(reach: Float) {
        laidOut.set(picture)
        laidOutReach = reach
        val p = picture
        val top = along(topPixels, p.left, p.right)
        val bottom = along(bottomPixels, p.left, p.right)
        val left = along(leftPixels, p.top, p.bottom)
        val right = along(rightPixels, p.top, p.bottom)
        shaders[TOP] = lit(top, fade(0f, p.top, 0f, p.top - reach))
        shaders[RIGHT] = lit(right, fade(p.right, 0f, p.right + reach, 0f))
        shaders[BOTTOM] = lit(bottom, fade(0f, p.bottom, 0f, p.bottom + reach))
        shaders[LEFT] = lit(left, fade(p.left, 0f, p.left - reach, 0f))
        // Past its ends a strip holds its end colour, which is the corner's,
        // so the corners borrow the top and bottom strips and fade round the
        // picture's corner on the same curve: where a corner meets a band,
        // the two are the same colour at the same brightness.
        shaders[TOP_LEFT] = lit(top, fadeAround(p.left, p.top, reach))
        shaders[TOP_RIGHT] = lit(top, fadeAround(p.right, p.top, reach))
        shaders[BOTTOM_RIGHT] = lit(bottom, fadeAround(p.right, p.bottom, reach))
        shaders[BOTTOM_LEFT] = lit(bottom, fadeAround(p.left, p.bottom, reach))
    }

    private companion object {
        const val TOP = 0
        const val RIGHT = 1
        const val BOTTOM = 2
        const val LEFT = 3
        const val TOP_LEFT = 4
        const val TOP_RIGHT = 5
        const val BOTTOM_RIGHT = 6
        const val BOTTOM_LEFT = 7
        const val SHAPES = 8
    }
}

/**
 * A strip's [pixels] stretched along one edge, from [from] to [to] — across
 * for a strip lying down, down for one standing up — with its first and last
 * pixels centred on the two ends, because [BleedStrips] samples run corner
 * to corner. Beyond the ends it holds the end colours.
 */
private fun along(pixels: Bitmap, from: Float, to: Float): Shader {
    val across = pixels.width > 1
    val step = (to - from) / ((if (across) pixels.width else pixels.height) - 1)
    val toEdge = Matrix().apply {
        if (across) {
            setScale(step, 1f)
            postTranslate(from - step / 2f, 0f)
        } else {
            setScale(1f, step)
            postTranslate(0f, from - step / 2f)
        }
    }
    return BitmapShader(pixels, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
        setLocalMatrix(toEdge)
        // Blend between neighbouring colours rather than step from one to the
        // next, which drew a column per sample. Set here rather than through
        // the paint's filter flag: inside a ComposeShader that flag did not
        // reach the bitmap (seen on the emulator, API 36).
        filterMode = BitmapShader.FILTER_MODE_LINEAR
    }
}

/** [falloff] in a straight line, from full at ([x0], [y0]) to nothing at ([x1], [y1]). */
private fun fade(x0: Float, y0: Float, x1: Float, y1: Float): Shader =
    LinearGradient(x0, y0, x1, y1, FADE_COLORS, FADE_STOPS, Shader.TileMode.CLAMP)

/** [falloff] in every direction from ([x], [y]), gone by [reach]. */
private fun fadeAround(x: Float, y: Float, reach: Float): Shader =
    RadialGradient(x, y, reach, FADE_COLORS, FADE_STOPS, Shader.TileMode.CLAMP)

/** [colour] showing only as strongly as [fade] is opaque. */
private fun lit(colour: Shader, fade: Shader): Shader = ComposeShader(fade, colour, PorterDuff.Mode.SRC_IN)

/**
 * How the light fades with distance from the picture, [t] running from 0 at
 * its edge to 1 at [REACH]: a smoothstep run backwards, level at both ends.
 * Level at the picture, so the light seems to come from behind it; level
 * where it runs out, so no line marks where the light stops. It is the
 * profile the zones made between them when each was drawn as its own pool
 * of light and they overlapped, so the light reaches as far as it did.
 */
private fun falloff(t: Float): Float {
    val x = t.coerceIn(0f, 1f)
    return 1f - x * x * (3f - 2f * x)
}

/** [falloff] as gradient stops. A gradient runs in straight lines between its stops, and two dozen make the curve. */
private const val FADE_STEPS = 24
private val FADE_STOPS = FloatArray(FADE_STEPS + 1) { it / FADE_STEPS.toFloat() }
private val FADE_COLORS = IntArray(FADE_STEPS + 1) { ((falloff(FADE_STOPS[it]) * 255f).roundToInt() shl 24) or 0xFFFFFF }

/** How far the light reaches from the picture's edge: past the bars of a scope film held sideways, not to the screen's own edge. */
private val REACH = 220.dp

/** How far each band reaches in under the picture, where nothing shows. */
private val TUCK = 1.dp

/** How long a zone takes to cover about two thirds of the way to a new colour. */
private const val RESPONSE_MS = 350f

/**
 * The light's strength in letterbox bars. Not full: the bars sit beside the
 * film in your eyeline, and a light as bright as the picture competes with
 * it rather than framing it.
 */
private const val SPILL_STRENGTH = 0.66f

/**
 * Behind the player's own page — the windowed player, and the landscape one
 * with its folder beside the picture — where words sit on the light. Gentler
 * than in the bars: at the bars' strength a white edge beside the picture
 * washed out the grey "Next in this folder" next to it.
 */
private const val PAGE_STRENGTH = 0.48f

/** Halfway down the windowed page: the light is still there, but the words win. */
private val PAGE_SCRIM = Color(0x66000000)
