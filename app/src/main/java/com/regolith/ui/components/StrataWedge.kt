package com.regolith.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The Strata Wedge: the app's mark, point right, concave back edge, cut
 * into five bands of rock with the ground showing through between them.
 *
 * DRAWN rather than shipped as a drawable, for the same reason as
 * [OrbitArt]: a vector asset is one fixed picture, and this one has to come
 * apart. The splash slides each band in separately, which a
 * `VectorDrawable` cannot do without an `AnimatedVectorDrawable` per
 * variation. Drawing it also means no bitmap to upscale — the old splash
 * stretched a 427x640 photograph to fill the screen, which is what made it
 * look soft.
 *
 * Geometry is `res/drawable/rg_wedge_white.xml` to the number, in that
 * file's 100x100 viewport, so the launcher icon and this stay the same
 * mark. Width and height scale independently, exactly as the drawable does
 * at its natural 58x78.
 *
 * Web analogy: inline SVG whose `<rect>`s you can transform, rather than an
 * `<img>` you can only fade.
 *
 * @param bandOffsetX horizontal displacement per band, in viewport units
 *   (0 = home, +-100 = fully outside the mark). The splash's entrance.
 * @param bandAlpha opacity multiplier per band, on top of the band's own.
 */
@Composable
fun StrataWedge(
    modifier: Modifier = Modifier,
    width: Dp = 58.dp,
    height: Dp = 78.dp,
    tint: Color = Color.White,
    bandOffsetX: (Int) -> Float = { 0f },
    bandAlpha: (Int) -> Float = { 1f },
) {
    Canvas(
        modifier
            .size(width = width, height = height)
            .semantics { contentDescription = "The Regolith wedge" }
            .testTag("strata_wedge"),
    ) {
        // One unit = one unit of the drawable's viewport, so every number
        // below is the number in the XML.
        val ux = size.width / VIEWPORT
        val uy = size.height / VIEWPORT
        val wedge = Path().apply {
            moveTo(4f * ux, 2f * uy)
            // The concave back edge: what stops it reading as a plain triangle.
            quadraticTo(30f * ux, 50f * uy, 4f * ux, 98f * uy)
            lineTo(98f * ux, 50f * uy)
            close()
        }
        clipPath(wedge) {
            BANDS.forEachIndexed { index, band ->
                // Bands run the full width and are clipped to the wedge, so
                // each picks up the taper without any geometry of its own.
                drawRect(
                    color = tint.copy(alpha = band.alpha * bandAlpha(index).coerceIn(0f, 1f)),
                    topLeft = Offset(bandOffsetX(index) * ux, band.y * uy),
                    size = Size(size.width, band.height * uy),
                )
            }
        }
    }
}

/** One band of rock: where it sits in the viewport, and how solid it is. */
private data class Band(val y: Float, val height: Float, val alpha: Float)

private const val VIEWPORT = 100f

/**
 * Five bands, three parts ink to one part gap. The dimmer ones are the
 * drawable's `#9EFFFFFF`, which is 62% white.
 */
private val BANDS = listOf(
    Band(y = 0f, height = 16f, alpha = 1f),
    Band(y = 23.5f, height = 13f, alpha = 0.62f),
    Band(y = 43.5f, height = 17f, alpha = 1f),
    Band(y = 68.5f, height = 12f, alpha = 0.62f),
    Band(y = 87f, height = 13f, alpha = 1f),
)

/** How many bands the mark has, for anything driving them one at a time. */
const val STRATA_BAND_COUNT = 5
