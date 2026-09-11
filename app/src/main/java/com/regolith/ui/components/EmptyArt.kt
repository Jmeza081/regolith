package com.regolith.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.regolith.ui.theme.RegolithTheme
import com.regolith.ui.theme.scaledDp

/**
 * Illustrations for the empty states. They are DRAWN, not shipped as
 * drawables: a vector asset would carry its own hard-coded greys, and the
 * whole point of these is that they take the theme's colours and sit a
 * step quieter than the text above them. Drawing them also costs the APK
 * nothing and keeps them crisp at any size.
 *
 * Web analogy: inline SVG with `currentColor`, rather than an `<img>`.
 */

/** The design box every drawing here is laid out in, then scaled to fit. */
private const val ART_W = 240f
private const val ART_H = 180f

/**
 * "Nothing in orbit": a regolith-grey moon crossed by a dashed ring, with
 * a few survey marks around it. Used by the "On this device" empty state,
 * where nothing has been kept yet — the ring is a path with nothing on it,
 * which is the difference between "empty" and "broken".
 *
 * The dash is deliberately the same 6/4 as [CardStyle.Empty]'s border, so
 * the drawing and the card it sits in read as one object.
 *
 * @param modifier size it with this; it keeps the 4:3 box whatever you pass.
 * @param width the drawing's width. The height follows at 3:4.
 */
@Composable
fun OrbitArt(modifier: Modifier = Modifier, width: androidx.compose.ui.unit.Dp = 172.scaledDp()) {
    val colors = RegolithTheme.colors
    Canvas(
        modifier
            .size(width = width, height = width * (ART_H / ART_W))
            .semantics { contentDescription = "A moon on an empty orbit" }
            .testTag("empty_art_orbit"),
    ) {
        // One unit = one pixel of the design box, so every number below is
        // the number from the drawing and the whole thing scales together.
        val u = size.width / ART_W
        fun p(x: Float, y: Float) = Offset(x * u, y * u)

        val ring = Stroke(
            width = 2f * u,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(7f * u, 6f * u)),
        )
        // Tilted, because a ring drawn flat reads as a plate rather than a path.
        rotate(degrees = -16f, pivot = p(120f, 92f)) {
            drawOval(
                color = colors.raised,
                topLeft = p(32f, 56f),
                size = Size(176f * u, 72f * u),
                style = ring,
            )
        }
        // The moon covers the far side of the ring, which is what gives the
        // flat drawing its depth without a single gradient.
        drawCircle(color = colors.raised, radius = 40f * u, center = p(120f, 92f))
        // Scattered and unequal on purpose: four evenly spaced craters read
        // as the face of a die rather than as a surface.
        drawCircle(color = colors.skeleton, radius = 8.5f * u, center = p(104f, 77f))
        drawCircle(color = colors.skeleton, radius = 5f * u, center = p(137f, 103f))
        drawCircle(color = colors.skeleton, radius = 3.2f * u, center = p(99f, 101f))
        drawCircle(color = colors.skeleton, radius = 2.6f * u, center = p(118f, 112f))
        drawCircle(color = colors.skeleton, radius = 2f * u, center = p(132f, 76f))

        // Survey marks: crosses near, dots far, so the field has a depth order.
        plus(p(46f, 46f), 6f * u, colors.raised, 2f * u)
        plus(p(201f, 34f), 5f * u, colors.raised, 2f * u)
        plus(p(187f, 142f), 5f * u, colors.raised, 2f * u)
        drawCircle(color = colors.raised, radius = 2f * u, center = p(66f, 34f))
        drawCircle(color = colors.raised, radius = 2f * u, center = p(163f, 150f))
        drawCircle(color = colors.raised, radius = 1.6f * u, center = p(212f, 112f))
    }
}

/** One survey mark: a plus of [arm] half-length centred on [at]. */
private fun DrawScope.plus(at: Offset, arm: Float, color: androidx.compose.ui.graphics.Color, stroke: Float) {
    drawLine(color, Offset(at.x - arm, at.y), Offset(at.x + arm, at.y), strokeWidth = stroke, cap = StrokeCap.Round)
    drawLine(color, Offset(at.x, at.y - arm), Offset(at.x, at.y + arm), strokeWidth = stroke, cap = StrokeCap.Round)
}
