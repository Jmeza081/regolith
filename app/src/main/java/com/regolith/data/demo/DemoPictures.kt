package com.regolith.data.demo

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import androidx.core.graphics.createBitmap
import androidx.core.graphics.withRotation
import java.io.ByteArrayOutputStream
import kotlin.random.Random

/**
 * The demo library's photos, painted when it is installed rather than
 * bundled: a few dozen pictures would add megabytes to the app, and these
 * are a handful of gradients and shapes. Each [Scene] is a kind of place —
 * a sunset over the sea, a city of painted houses, snow in the mountains,
 * a field — varied by a seeded [Random], so the demo is the same pictures
 * every time it is installed.
 *
 * Web analogy: generating placeholder images on a `<canvas>` instead of
 * shipping them.
 */
internal object DemoPictures {

    enum class Scene { SEA, CITY, SNOW, FIELD }

    /** [scene] at [width] × [height], as JPEG bytes. */
    fun jpeg(scene: Scene, width: Int, height: Int, random: Random): ByteArray {
        val bitmap = createBitmap(width, height)
        try {
            val canvas = Canvas(bitmap)
            when (scene) {
                Scene.SEA -> sea(canvas, width.toFloat(), height.toFloat(), random)
                Scene.CITY -> city(canvas, width.toFloat(), height.toFloat(), random)
                Scene.SNOW -> snow(canvas, width.toFloat(), height.toFloat(), random)
                Scene.FIELD -> field(canvas, width.toFloat(), height.toFloat(), random)
            }
            return ByteArrayOutputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, QUALITY, out)
                out.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }

    /** A sun going down over the sea, a beach, and a kayak or two drawn up on it. */
    private fun sea(c: Canvas, w: Float, h: Float, r: Random) {
        val horizon = h * r.between(0.5f, 0.62f)
        val shore = h * r.between(0.78f, 0.86f)
        val warm = r.between(18f, 38f)
        gradient(c, 0f, 0f, w, horizon, hsv(warm + 12f, 0.42f, 0.97f), hsv(warm, 0.58f, 0.9f))
        // The sun, its glow, and its path on the water.
        val sunX = w * r.between(0.3f, 0.72f)
        val sunY = horizon - h * r.between(0.06f, 0.16f)
        val sunR = minOf(w, h) * r.between(0.05f, 0.08f)
        glow(c, sunX, sunY, sunR * 3.5f, Color.argb(150, 255, 236, 200))
        circle(c, sunX, sunY, sunR, Color.rgb(255, 238, 200))
        gradient(c, 0f, horizon, w, shore, hsv(214f, 0.42f, 0.5f), hsv(222f, 0.55f, 0.26f))
        val streak = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, horizon, 0f, shore, Color.argb(170, 255, 225, 180), Color.argb(0, 255, 225, 180), Shader.TileMode.CLAMP)
        }
        c.drawRect(sunX - sunR * 0.55f, horizon, sunX + sunR * 0.55f, shore, streak)
        gradient(c, 0f, shore, w, h, hsv(36f, 0.32f, 0.78f), hsv(30f, 0.42f, 0.52f))
        repeat(r.nextInt(1, 3)) {
            val color = if (r.nextBoolean()) Color.rgb(222, 92, 64) else Color.rgb(244, 186, 72)
            val kw = w * r.between(0.22f, 0.36f)
            val kh = kw * 0.13f
            val x = w * r.between(0.08f, 0.6f)
            val y = shore + (h - shore) * r.between(0.25f, 0.7f)
            c.withRotation(r.between(-8f, 4f), x + kw / 2, y + kh / 2) {
                drawRoundRect(RectF(x, y, x + kw, y + kh), kh / 2, kh / 2, fill(color))
            }
        }
    }

    /** A row of painted houses, their windows, and sometimes a yellow tram. */
    private fun city(c: Canvas, w: Float, h: Float, r: Random) {
        gradient(c, 0f, 0f, w, h * 0.7f, hsv(r.between(195f, 210f), 0.32f, 0.96f), hsv(r.between(28f, 40f), 0.22f, 0.98f))
        val street = h * 0.86f
        val palette = intArrayOf(
            Color.rgb(236, 196, 92), Color.rgb(226, 146, 128), Color.rgb(196, 98, 72),
            Color.rgb(134, 178, 210), Color.rgb(240, 232, 214), Color.rgb(168, 196, 150),
        )
        var x = -w * 0.05f
        while (x < w) {
            val bw = w * r.between(0.14f, 0.26f)
            val top = h * r.between(0.22f, 0.5f)
            val wall = palette[r.nextInt(palette.size)]
            c.drawRect(x, top, x + bw, street, fill(wall))
            c.drawRect(x, top, x + bw, top + h * 0.025f, fill(darker(wall, 0.7f)))
            // Windows in a grid, a little darker than the wall.
            val cols = r.nextInt(2, 4)
            val rows = ((street - top) / (h * 0.09f)).toInt().coerceAtLeast(1)
            val ww = bw / (cols * 2f + 1)
            for (i in 0 until cols) {
                for (j in 0 until rows - 1) {
                    val wx = x + ww * (1 + i * 2)
                    val wy = top + h * 0.05f + j * h * 0.09f
                    c.drawRect(wx, wy, wx + ww, wy + h * 0.05f, fill(darker(wall, 0.55f)))
                }
            }
            x += bw
        }
        c.drawRect(0f, street, w, h, fill(Color.rgb(112, 108, 104)))
        if (r.nextBoolean()) {
            val tw = w * r.between(0.45f, 0.6f)
            val tx = w * r.between(0.05f, 0.4f)
            val th = h * 0.12f
            val ty = street - th * 0.9f
            c.drawRoundRect(RectF(tx, ty, tx + tw, ty + th), th * 0.2f, th * 0.2f, fill(Color.rgb(246, 196, 42)))
            for (i in 0 until 4) {
                val wx = tx + tw * (0.08f + i * 0.23f)
                c.drawRect(wx, ty + th * 0.18f, wx + tw * 0.14f, ty + th * 0.55f, fill(Color.rgb(70, 82, 96)))
            }
        }
    }

    /** Snowy ranges under a cold sky, pines, and snow in front. */
    private fun snow(c: Canvas, w: Float, h: Float, r: Random) {
        gradient(c, 0f, 0f, w, h * 0.6f, hsv(r.between(198f, 212f), 0.48f, 0.88f), hsv(200f, 0.12f, 0.98f))
        for (layer in 0 until 3) {
            val base = h * (0.5f + layer * 0.1f)
            val peakTop = h * (0.18f + layer * 0.12f)
            val path = Path().apply { moveTo(0f, base) }
            var x = 0f
            while (x < w) {
                val step = w * r.between(0.12f, 0.24f)
                path.lineTo(x + step / 2, peakTop + h * r.between(0f, 0.12f))
                path.lineTo(x + step, base - h * r.between(0f, 0.05f))
                x += step
            }
            path.lineTo(w, h)
            path.lineTo(0f, h)
            path.close()
            val tone = 0.92f - layer * 0.12f
            c.drawPath(path, fill(hsv(208f, 0.12f + layer * 0.08f, tone)))
        }
        gradient(c, 0f, h * 0.78f, w, h, Color.rgb(250, 252, 255), Color.rgb(214, 228, 242))
        repeat(r.nextInt(3, 7)) {
            val px = w * r.nextFloat()
            val py = h * r.between(0.76f, 0.92f)
            val ph = h * r.between(0.08f, 0.16f)
            val path = Path().apply {
                moveTo(px, py - ph)
                lineTo(px + ph * 0.32f, py)
                lineTo(px - ph * 0.32f, py)
                close()
            }
            c.drawPath(path, fill(Color.rgb(38, 72, 58)))
        }
    }

    /** Rolling green hills and a lone tree. */
    private fun field(c: Canvas, w: Float, h: Float, r: Random) {
        gradient(c, 0f, 0f, w, h * 0.6f, hsv(r.between(190f, 210f), 0.4f, 0.95f), hsv(r.between(40f, 55f), 0.18f, 0.98f))
        for (layer in 0 until 3) {
            val y = h * (0.55f + layer * 0.12f)
            val path = Path().apply {
                moveTo(0f, y)
                quadTo(w * r.between(0.2f, 0.5f), y - h * r.between(0.05f, 0.14f), w * r.between(0.55f, 0.75f), y)
                quadTo(w * 0.9f, y + h * 0.04f, w, y - h * 0.03f)
                lineTo(w, h)
                lineTo(0f, h)
                close()
            }
            c.drawPath(path, fill(hsv(r.between(85f, 120f), 0.45f + layer * 0.1f, 0.72f - layer * 0.12f)))
        }
        val tx = w * r.between(0.2f, 0.8f)
        val ty = h * r.between(0.48f, 0.6f)
        val tr = minOf(w, h) * r.between(0.06f, 0.1f)
        c.drawRect(tx - tr * 0.12f, ty, tx + tr * 0.12f, ty + tr * 1.6f, fill(Color.rgb(92, 64, 44)))
        circle(c, tx, ty, tr, Color.rgb(54, 110, 60))
    }

    private fun gradient(c: Canvas, left: Float, top: Float, right: Float, bottom: Float, from: Int, to: Int) {
        val paint = Paint().apply { shader = LinearGradient(0f, top, 0f, bottom, from, to, Shader.TileMode.CLAMP) }
        c.drawRect(left, top, right, bottom, paint)
    }

    private fun glow(c: Canvas, x: Float, y: Float, radius: Float, color: Int) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(x, y, radius, color, Color.argb(0, Color.red(color), Color.green(color), Color.blue(color)), Shader.TileMode.CLAMP)
        }
        c.drawCircle(x, y, radius, paint)
    }

    private fun circle(c: Canvas, x: Float, y: Float, radius: Float, color: Int) = c.drawCircle(x, y, radius, fill(color))

    private fun fill(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }

    private fun hsv(hue: Float, saturation: Float, value: Float): Int = Color.HSVToColor(floatArrayOf(hue, saturation, value))

    private fun darker(color: Int, f: Float): Int =
        Color.rgb((Color.red(color) * f).toInt(), (Color.green(color) * f).toInt(), (Color.blue(color) * f).toInt())

    private fun Random.between(from: Float, until: Float): Float = from + nextFloat() * (until - from)

    /** Good enough to look like a photo at a glance, small enough to install in a moment. */
    private const val QUALITY = 86
}
