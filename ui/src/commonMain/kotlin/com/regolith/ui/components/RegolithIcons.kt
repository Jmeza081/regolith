package com.regolith.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * The glyphs the shared components draw themselves, as code both platforms
 * can render.
 *
 * Each is a copy of the phone's `app/src/main/res/drawable/rg_ic_*.xml` of the
 * same name: the same 24-unit path data, stroke width, caps and joins. On
 * Android an XML vector is parsed into exactly this kind of [ImageVector]
 * before it is drawn, so the pixels do not change; the desktop has no Android
 * resources to parse. Web analogy: an inline SVG component instead of an
 * `<img src>` that the bundler resolves.
 *
 * Screens still pass their own icons in, as a `Painter`; only glyphs built
 * into a component live here. Change an XML file and its copy together.
 */
object RegolithIcons {
    /** `rg_ic_check`: a tick, for the chosen option in a sheet. */
    val Check: ImageVector by lazy { stroked("Check", "m5 12.5 4.5 4.5L19 7") }

    /** `rg_ic_alert`: an exclamation mark in a circle, for notice and error cards. */
    val Alert: ImageVector by lazy {
        stroked("Alert", "M12 8v5m0 3h.01", "M3.0,12.0 a9.0,9.0 0 1 0 18.0,0 a9.0,9.0 0 1 0 -18.0,0 z")
    }

    /** `rg_ic_browse`: a folder, for the collection badge on a tile. */
    val Browse: ImageVector by lazy {
        stroked(
            "Browse",
            "M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z",
            width = 2.5f,
            cap = StrokeCap.Butt,
        )
    }

    /** `rg_ic_back`: a left arrow, for the top bar's back button. */
    val Back: ImageVector by lazy { stroked("Back", "M19 12H5m0 0 6-6m-6 6 6 6") }

    /** `rg_ic_play`: a solid triangle, for Play all and the resume card. */
    val Play: ImageVector by lazy { filled("Play", "M7 4.5v15l13-7.5z") }

    /** `rg_ic_shuffle`: two crossing arrows (Lucide "shuffle"), for Play all's shuffle choice. */
    val Shuffle: ImageVector by lazy {
        stroked(
            "Shuffle",
            "M2 18h1.4c1.3 0 2.5-.6 3.3-1.7l6.1-8.6c.8-1.1 2-1.7 3.3-1.7H22",
            "m18 2 4 4-4 4",
            "M2 6h1.9c1.5 0 2.9.9 3.6 2.2",
            "M22 18h-5.9c-1.3 0-2.6-.7-3.3-1.8l-.5-.8",
            "m18 14 4 4-4 4",
        )
    }

    /** `rg_ic_chevron_right`: a right chevron, for a row you can walk into. */
    val ChevronRight: ImageVector by lazy { stroked("ChevronRight", "m9 6 6 6-6 6") }

    // A white fill and no stroke, as in the XML (it names a stroke width but no
    // stroke colour, so nothing is stroked).
    private fun filled(name: String, path: String): ImageVector = ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        addPath(
            pathData = addPathNodes(path),
            fill = SolidColor(Color.White),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Butt,
            strokeLineJoin = StrokeJoin.Miter,
        )
    }.build()

    // White strokes and no fill, as in the XML; `Icon` tints them at draw time.
    private fun stroked(
        name: String,
        vararg paths: String,
        width: Float = 2f,
        cap: StrokeCap = StrokeCap.Round,
    ): ImageVector = ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        paths.forEach { data ->
            addPath(
                pathData = addPathNodes(data),
                stroke = SolidColor(Color.White),
                strokeLineWidth = width,
                strokeLineCap = cap,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
    }.build()
}
