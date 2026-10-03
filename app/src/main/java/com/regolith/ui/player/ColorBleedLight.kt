package com.regolith.ui.player

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.BlendMode
import android.graphics.Matrix
import android.graphics.Paint
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
import androidx.core.graphics.withSave
import com.regolith.domain.playback.BleedZone
import com.regolith.domain.playback.ColorBleed
import com.regolith.ui.theme.RegolithTheme
import java.nio.ByteBuffer
import kotlin.math.sqrt

/**
 * Color bleed's light: a soft glow from each zone along the picture's edges
 * out into the space around it, the way an LED strip behind a TV lights the
 * wall. It is drawn behind the picture, so the only light anyone sees is the
 * light that leaves its edges.
 *
 * Each zone eases to its newest colour every frame ([ColorBleed.ease]),
 * which is what keeps one bright frame from flashing the room. The easing
 * stops once every zone has arrived, so a paused film costs nothing.
 *
 * Cheap on purpose. Mirror re-blurs a full-screen layer on every sample;
 * this paints two dozen soft ellipses from one 128×128 falloff mask, never
 * where the picture is, with no blur pass at all.
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

    val paint = remember { lightPaint() }
    val ground = RegolithTheme.colors.ground
    Box(modifier.onGloballyPositioned { origin = it.positionInWindow() }) {
        Canvas(Modifier.fillMaxSize().testTag("player_ambient_bleed")) {
            tick
            val s = sample ?: return@Canvas
            val eased = current ?: return@Canvas
            if (eased.size != s.colors.size * 3) return@Canvas
            // The picture in this box's own coordinates: the sampler measured
            // it in the window's, because the video is somebody else's View.
            val picture = RectF(
                s.picture.left - origin.x, s.picture.top - origin.y,
                s.picture.right - origin.x, s.picture.bottom - origin.y,
            )
            if (picture.width() <= 0f || picture.height() <= 0f) return@Canvas
            val reach = REACH.toPx()
            val inset = 2.dp.toPx()
            val strength = if (spill) SPILL_STRENGTH else PAGE_STRENGTH
            drawIntoCanvas { canvas ->
                val c = canvas.nativeCanvas
                c.withSave {
                    // Under the picture nothing would show, so nothing is drawn there.
                    clipOutRect(picture)
                    for (i in s.zones.indices) {
                        val z = s.zones[i]
                        paint.color = (ColorBleed.colorAt(eased, i) and 0x00FFFFFF) or ((strength * 255).toInt() shl 24)
                        val cx = picture.left + (z.left + z.right) / 2f * picture.width()
                        val cy = picture.top + (z.top + z.bottom) / 2f * picture.height()
                        val zw = (z.right - z.left) * picture.width()
                        val zh = (z.bottom - z.top) * picture.height()
                        // Hung just inside the edge, wide enough to overlap its
                        // neighbours by half, so the strip reads as one glow
                        // rather than a row of torches.
                        when (z.edge) {
                            BleedZone.Edge.TOP -> glow(cx, picture.top + inset, zw * ZONE_OVERLAP, reach, paint)
                            BleedZone.Edge.BOTTOM -> glow(cx, picture.bottom - inset, zw * ZONE_OVERLAP, reach, paint)
                            BleedZone.Edge.LEFT -> glow(picture.left + inset, cy, reach * SIDE_REACH, zh * SIDE_LENGTH + reach * SIDE_SPILL, paint)
                            BleedZone.Edge.RIGHT -> glow(picture.right - inset, cy, reach * SIDE_REACH, zh * SIDE_LENGTH + reach * SIDE_SPILL, paint)
                        }
                    }
                }
            }
        }
        if (!spill) {
            // Behind the windowed page the light belongs to the picture: it
            // fades to the app's own ground on the way down, as Mirror's does.
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Transparent, 0.5f to PAGE_SCRIM, 1f to ground)))
        }
    }
}

/** One soft ellipse of light centred on ([x], [y]), [rx] by [ry] to where it fades out. */
private fun android.graphics.Canvas.glow(x: Float, y: Float, rx: Float, ry: Float, paint: Paint) {
    if (rx <= 0f || ry <= 0f) return
    withSave {
        translate(x, y)
        scale(rx, ry)
        drawRect(-1f, -1f, 1f, 1f, paint)
    }
}

/**
 * The paint every zone shares: an alpha-only falloff mask, so the colour
 * comes from the paint itself and changing it allocates nothing — two dozen
 * fresh gradient shaders a frame would be garbage for the collector at 60 Hz.
 * Screen-blended, so where two zones overlap they mix the way light does
 * instead of one painting over the other.
 */
private fun lightPaint(): Paint {
    val mask = falloffMask()
    val toUnitSquare = Matrix().apply {
        setScale(2f / MASK_SIZE, 2f / MASK_SIZE)
        postTranslate(-1f, -1f)
    }
    return Paint().apply {
        isFilterBitmap = true
        blendMode = BlendMode.SCREEN
        shader = BitmapShader(mask, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply { setLocalMatrix(toUnitSquare) }
    }
}

/**
 * How a zone's light fades with distance from where it is hung: strongest at
 * the edge of the picture, gone by [REACH]. The same curve the comparison
 * page used (1 → 0.62 → 0.22 → 0 at 0, 0.3, 0.62 and 1 of the way out).
 */
private fun falloffMask(): Bitmap {
    val bytes = ByteArray(MASK_SIZE * MASK_SIZE)
    val half = MASK_SIZE / 2f
    for (y in 0 until MASK_SIZE) {
        for (x in 0 until MASK_SIZE) {
            val dx = (x + 0.5f - half) / half
            val dy = (y + 0.5f - half) / half
            bytes[y * MASK_SIZE + x] = (falloff(sqrt(dx * dx + dy * dy)) * 255f).toInt().toByte()
        }
    }
    return createBitmap(MASK_SIZE, MASK_SIZE, Bitmap.Config.ALPHA_8).apply {
        copyPixelsFromBuffer(ByteBuffer.wrap(bytes))
    }
}

private fun falloff(d: Float): Float = when {
    d >= 1f -> 0f
    d >= 0.62f -> 0.22f * (1f - (d - 0.62f) / 0.38f)
    d >= 0.3f -> 0.62f - 0.40f * (d - 0.3f) / 0.32f
    else -> 1f - 0.38f * d / 0.3f
}

private const val MASK_SIZE = 128

/** How far the light reaches from the picture's edge: past the bars of a scope film held sideways, not to the screen's own edge. */
private val REACH = 220.dp

/** A top or bottom zone's light is this many zone-widths across, so neighbours overlap. */
private const val ZONE_OVERLAP = 1.6f

/** A side zone throws its light this much of [REACH] sideways… */
private const val SIDE_REACH = 0.9f

/** …along this many zone-heights… */
private const val SIDE_LENGTH = 1.3f

/** …plus this much of [REACH] past the picture's corners, which is what lights the corners of the bars. */
private const val SIDE_SPILL = 0.35f

/** How long a zone takes to cover about two thirds of the way to a new colour. */
private const val RESPONSE_MS = 350f

/**
 * The light's strength in letterbox bars. Not full: the bars sit beside the
 * film in your eyeline, and a light as bright as the picture competes with
 * it rather than framing it.
 */
private const val SPILL_STRENGTH = 0.62f

/**
 * Behind the player's own page — the windowed player, and the landscape one
 * with its folder beside the picture — where words sit on the light. Gentler
 * than in the bars: at the bars' strength a white edge beside the picture
 * washed out the grey "Next in this folder" next to it.
 */
private const val PAGE_STRENGTH = 0.48f

/** Halfway down the windowed page: the light is still there, but the words win. */
private val PAGE_SCRIM = Color(0x66000000)
