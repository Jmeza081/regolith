package com.regolith.domain.playback

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * One light along the picture's edge, and the band of the picture it reads.
 * The bounds are fractions of the picture (0–1), so the same zone fits a
 * sample of any size.
 */
data class BleedZone(
    val edge: Edge,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    enum class Edge { TOP, RIGHT, BOTTOM, LEFT }
}

/**
 * The arithmetic behind [AmbientLight.COLOR_BLEED]: where the lights go
 * around the picture, what colour each one shines, and how it eases to a
 * new one. What a Govee or Ambilight box does with a camera, done here on a
 * tiny sample of the frame that is already on screen.
 *
 * Plain Kotlin on ARGB ints (web analogy: the same numbers a canvas
 * `getImageData` hands back), so all of it is unit-tested without a device.
 */
object ColorBleed {

    /** How many lights run round the picture — the LED count on a real strip. */
    const val ZONES = 24

    /** How deep a top or bottom zone reads into the picture, as a fraction of its height. */
    const val EDGE_DEPTH = 0.2f

    /** How deep a side zone reads in, as a fraction of the picture's width. */
    const val SIDE_DEPTH = 0.12f

    /** How far past the film's own grade the colour is pushed: the 45% Mirror's blur uses too. */
    const val VIVIDNESS = 0.45f

    /** Hue buckets, 20° each. */
    private const val HUES = 18
    private const val GREY = HUES
    private const val DARK = HUES + 1

    /** Below this brightness a pixel counts as black, and a mostly black band shines nothing. */
    private const val DARK_VALUE = 0.07f
    private const val DARK_WEIGHT = 0.35f

    /** Below this saturation a pixel is white or grey rather than a hue. */
    private const val GREY_SATURATION = 0.2f
    private const val GREY_WEIGHT = 0.6f

    /** The smallest change, per channel, still worth a redraw: about half a step of 8-bit colour. */
    private const val SETTLED = 1f / 512f

    /** A black zone's light: not quite zero, so the screen stays the colour of a dark room. */
    const val BLACK = 0xFF050505.toInt()

    /**
     * [count] zones around a picture [aspect] wide (width / height), shared
     * between the edges by their length — so a scope film gets more along
     * the top than down the sides — in the order a strip runs behind a TV:
     * top left to right, right side down, bottom right to left, left side up.
     *
     * The total can come out one off [count] when the edges do not divide it
     * evenly; the list is what counts.
     */
    fun zones(count: Int = ZONES, aspect: Float): List<BleedZone> {
        val a = aspect.coerceIn(0.4f, 4f)
        val side = max(1, (count / (2 * a + 2)).roundToInt())
        val top = max(2, ((count - 2 * side) / 2f).roundToInt())
        return buildList {
            for (i in 0 until top) add(BleedZone(BleedZone.Edge.TOP, i / top.toFloat(), 0f, (i + 1) / top.toFloat(), EDGE_DEPTH))
            for (i in 0 until side) add(BleedZone(BleedZone.Edge.RIGHT, 1f - SIDE_DEPTH, i / side.toFloat(), 1f, (i + 1) / side.toFloat()))
            for (i in 0 until top) add(BleedZone(BleedZone.Edge.BOTTOM, 1f - (i + 1) / top.toFloat(), 1f - EDGE_DEPTH, 1f - i / top.toFloat(), 1f))
            for (i in 0 until side) add(BleedZone(BleedZone.Edge.LEFT, 0f, 1f - (i + 1) / side.toFloat(), SIDE_DEPTH, 1f - i / side.toFloat()))
        }
    }

    /** The light each zone shines, read from a [width] × [height] sample of the picture. */
    fun colors(pixels: IntArray, width: Int, height: Int, zones: List<BleedZone>, vividness: Float = VIVIDNESS): IntArray =
        IntArray(zones.size) { lamp(dominant(pixels, width, height, zones[it]), vividness) }

    /**
     * The colour a zone is mostly made of, as ARGB.
     *
     * Not the average: the average of a green forest with dark trunks in it
     * is olive, and of a sunset over a dark city, brown. Instead each pixel
     * votes for its hue bucket, the vivid and bright ones counting for more,
     * and the zone takes the winning bucket's own average. White and grey
     * have a bucket of their own, so snow lights white rather than faintly
     * blue; black has one too, and a band that is mostly black comes back
     * [BLACK], because the room should go dark with the film.
     */
    fun dominant(pixels: IntArray, width: Int, height: Int, zone: BleedZone): Int {
        if (width <= 0 || height <= 0) return BLACK
        val x0 = floor(zone.left * width).toInt().coerceIn(0, width - 1)
        val x1 = max(x0 + 1, min(width, ceil(zone.right * width).toInt()))
        val y0 = floor(zone.top * height).toInt().coerceIn(0, height - 1)
        val y1 = max(y0 + 1, min(height, ceil(zone.bottom * height).toInt()))
        // weight, then weight × r, g, b, per bucket
        val acc = DoubleArray((HUES + 2) * 4)
        for (y in y0 until y1) {
            for (x in x0 until x1) {
                val p = pixels[y * width + x]
                val r = (p shr 16 and 0xFF) / 255f
                val g = (p shr 8 and 0xFF) / 255f
                val b = (p and 0xFF) / 255f
                val mx = max(r, max(g, b))
                val mn = min(r, min(g, b))
                val s = if (mx > 0f) (mx - mn) / mx else 0f
                val bucket: Int
                val weight: Float
                when {
                    mx < DARK_VALUE -> { bucket = DARK; weight = DARK_WEIGHT }
                    s < GREY_SATURATION -> { bucket = GREY; weight = mx * GREY_WEIGHT }
                    else -> {
                        bucket = (hue(r, g, b, mx, mn) * HUES).toInt() % HUES
                        weight = mx * (0.35f + s) * (0.6f + s)
                    }
                }
                val o = bucket * 4
                acc[o] += weight.toDouble()
                acc[o + 1] += (weight * r).toDouble()
                acc[o + 2] += (weight * g).toDouble()
                acc[o + 3] += (weight * b).toDouble()
            }
        }
        // A hue that straddles two buckets still wins: each scores half its neighbours too.
        var best = GREY
        var bestScore = acc[GREY * 4]
        for (k in 0 until HUES) {
            val score = acc[k * 4] + 0.5 * (acc[((k + HUES - 1) % HUES) * 4] + acc[((k + 1) % HUES) * 4])
            if (score > bestScore) { bestScore = score; best = k }
        }
        if (acc[DARK * 4] > bestScore || acc[best * 4] <= 0.0) return BLACK
        val o = best * 4
        val w = acc[o]
        return argb((acc[o + 1] / w).toFloat(), (acc[o + 2] / w).toFloat(), (acc[o + 3] / w).toFloat())
    }

    /**
     * A zone's colour as light. Film is graded for a screen you look at, not
     * for a lamp: left alone, most of it reads as haze. So the saturation is
     * pushed by [vividness] and the middle tones lifted, while black stays
     * black.
     */
    fun lamp(color: Int, vividness: Float = VIVIDNESS): Int {
        val r = (color shr 16 and 0xFF) / 255f
        val g = (color shr 8 and 0xFF) / 255f
        val b = (color and 0xFF) / 255f
        val mx = max(r, max(g, b))
        val mn = min(r, min(g, b))
        if (mx < 0.05f) return color or (0xFF shl 24)
        val h = hue(r, g, b, mx, mn)
        val s = min(1f, (if (mx > 0f) (mx - mn) / mx else 0f) * (1f + vividness))
        val v = min(1f, mx.pow(0.8f))
        return hsv(h, s, v)
    }

    /** Light being eased towards its target: r, g, b per zone, 0–1. Starts at the target. */
    fun seed(targets: IntArray): FloatArray = FloatArray(targets.size * 3).also { out ->
        for (i in targets.indices) {
            out[i * 3] = (targets[i] shr 16 and 0xFF) / 255f
            out[i * 3 + 1] = (targets[i] shr 8 and 0xFF) / 255f
            out[i * 3 + 2] = (targets[i] and 0xFF) / 255f
        }
    }

    /**
     * Moves [current] (from [seed]) toward [targets] by one frame of [dtMs],
     * covering about 63% of the way every [responseMs] — so one bright frame
     * does not flash the room, and a cut still lands within a beat.
     *
     * @return true while any zone is still visibly on its way, which is when
     *   the light needs redrawing.
     */
    fun ease(current: FloatArray, targets: IntArray, dtMs: Float, responseMs: Float): Boolean {
        val k = 1f - exp(-dtMs / responseMs)
        var moving = false
        for (i in targets.indices) {
            val t = targets[i]
            val tr = (t shr 16 and 0xFF) / 255f
            val tg = (t shr 8 and 0xFF) / 255f
            val tb = (t and 0xFF) / 255f
            val o = i * 3
            current[o] += (tr - current[o]) * k
            current[o + 1] += (tg - current[o + 1]) * k
            current[o + 2] += (tb - current[o + 2]) * k
            if (abs(tr - current[o]) > SETTLED || abs(tg - current[o + 1]) > SETTLED || abs(tb - current[o + 2]) > SETTLED) moving = true
        }
        return moving
    }

    /** Zone [i] of an eased [current], as ARGB. */
    fun colorAt(current: FloatArray, i: Int): Int = argb(current[i * 3], current[i * 3 + 1], current[i * 3 + 2])

    private fun hue(r: Float, g: Float, b: Float, mx: Float, mn: Float): Float {
        val d = mx - mn
        if (d == 0f) return 0f
        var h = when (mx) {
            r -> ((g - b) / d) % 6f
            g -> (b - r) / d + 2f
            else -> (r - g) / d + 4f
        } / 6f
        if (h < 0f) h += 1f
        return h
    }

    private fun hsv(h: Float, s: Float, v: Float): Int {
        val i = floor(h * 6f).toInt()
        val f = h * 6f - i
        val p = v * (1f - s)
        val q = v * (1f - f * s)
        val t = v * (1f - (1f - f) * s)
        return when (((i % 6) + 6) % 6) {
            0 -> argb(v, t, p)
            1 -> argb(q, v, p)
            2 -> argb(p, v, t)
            3 -> argb(p, q, v)
            4 -> argb(t, p, v)
            else -> argb(v, p, q)
        }
    }

    private fun argb(r: Float, g: Float, b: Float): Int =
        (0xFF shl 24) or
            ((r.coerceIn(0f, 1f) * 255f).roundToInt() shl 16) or
            ((g.coerceIn(0f, 1f) * 255f).roundToInt() shl 8) or
            (b.coerceIn(0f, 1f) * 255f).roundToInt()
}

/**
 * The light along each edge of the picture as an even run of colours, corner
 * to corner, so the zones blend into one another instead of standing side by
 * side as separate pools of light.
 *
 * Each colour is a weighted mix of the zones around it, the way the light of
 * neighbouring LEDs overlaps on a wall: a bell curve [SPREAD] zones wide,
 * measured round the picture the way a strip runs, so the mix carries on
 * round each corner with no seam. A zone's own colour still leads at its
 * centre (about 85% of it), halfway to the next zone the two are even, and
 * nothing of it is left two zones away. Zones of one colour make a perfectly
 * even band: no brighter column at each zone, no dip between them.
 *
 * Expects the zones in the order [ColorBleed.zones] lists them, each one step
 * round the picture. The weights depend only on that layout, so they are
 * worked out once, here; [fill] then runs every frame without allocating.
 *
 * @param samples how many colours each strip holds, corner to corner with
 *   both corners included. They are drawn stretched along the edge, blended
 *   in straight lines between neighbours, so a hundred or so is smooth along
 *   even the longest edge.
 */
class BleedStrips(zones: List<BleedZone>, val samples: Int = SAMPLES) {

    /** Left to right along the top. */
    val top = IntArray(samples)

    /** Top to bottom down the right side. */
    val right = IntArray(samples)

    /** Left to right along the bottom. */
    val bottom = IntArray(samples)

    /** Top to bottom down the left side. */
    val left = IntArray(samples)

    // For each sample of each strip (top, right, bottom, left in turn), the
    // [NEAREST] zones that light it and their share of its colour.
    private val zoneOf = IntArray(4 * samples * NEAREST)
    private val shareOf = FloatArray(4 * samples * NEAREST)

    init {
        val edges = zones.map { it.edge }
        val counts = BleedZone.Edge.entries.map { e -> edges.count { it == e } }
        require(counts.all { it > 0 }) { "every edge needs at least one zone" }
        require(edges == edges.sorted()) { "zones must run the way a strip does: top, right, bottom, left" }
        // Positions round the picture in zones, from the top-left corner:
        // the top runs from 0 to t, the right side on to t + r, and so on
        // back to the top-left corner at n, which is 0 again.
        val (t, r, b, l) = counts.map { it.toFloat() }
        for (j in 0 until samples) {
            val f = if (samples == 1) 0.5f else j.toFloat() / (samples - 1)
            weigh(0, j, f * t, zones.size)
            weigh(1, j, t + f * r, zones.size)
            // The bottom and left strips read against the way the strip runs.
            weigh(2, j, t + r + (1f - f) * b, zones.size)
            weigh(3, j, t + r + b + (1f - f) * l, zones.size)
        }
    }

    /** Works out which zones light sample [j] of [strip], at [at] zones round the picture, and how much. */
    private fun weigh(strip: Int, j: Int, at: Float, n: Int) {
        val o = (strip * samples + j) * NEAREST
        val first = floor(at).toInt() - NEAREST / 2
        var total = 0f
        for (k in 0 until NEAREST) {
            val zone = first + k // counted on past the end of the list, or before it, to wrap round
            val d = at - (zone + 0.5f)
            val w = exp(-d * d / (2f * SPREAD * SPREAD))
            zoneOf[o + k] = ((zone % n) + n) % n
            shareOf[o + k] = w
            total += w
        }
        for (k in 0 until NEAREST) shareOf[o + k] /= total
    }

    /** Fills the four strips from [colors], one ARGB colour per zone in the layout's order. */
    fun fill(colors: IntArray) {
        blend(top, 0, colors)
        blend(right, 1, colors)
        blend(bottom, 2, colors)
        blend(left, 3, colors)
    }

    private fun blend(out: IntArray, strip: Int, colors: IntArray) {
        for (j in 0 until samples) {
            val o = (strip * samples + j) * NEAREST
            var r = 0f
            var g = 0f
            var b = 0f
            for (k in o until o + NEAREST) {
                val c = colors[zoneOf[k]]
                val share = shareOf[k]
                r += share * (c shr 16 and 0xFF)
                g += share * (c shr 8 and 0xFF)
                b += share * (c and 0xFF)
            }
            out[j] = (0xFF shl 24) or (channel(r) shl 16) or (channel(g) shl 8) or channel(b)
        }
    }

    companion object {
        const val SAMPLES = 128

        /**
         * How far a zone's light spreads along the edge, in zone widths (one
         * standard deviation of the bell curve). Narrower, and each zone
         * holds its colour flat across its own width, which reads as a row of
         * columns; wider, and neighbouring colours mix to mud.
         */
        const val SPREAD = 0.45f

        /** Zones that light each sample: the nearest five, enough for [SPREAD]'s bell curve to have run out. */
        private const val NEAREST = 5

        private fun channel(v: Float): Int = v.roundToInt().coerceIn(0, 255)
    }
}
