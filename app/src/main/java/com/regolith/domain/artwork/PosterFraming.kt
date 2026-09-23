package com.regolith.domain.artwork

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** A width and height in pixels. The domain's own, so this file needs no Android or Compose import. */
data class Dimensions(val width: Float, val height: Float) {
    val isEmpty: Boolean get() = width <= 0f || height <= 0f
}

/** A rectangle of whole source pixels: what gets cut out of the frame. */
data class CropRect(val left: Int, val top: Int, val width: Int, val height: Int)

/**
 * Where the picture sits behind the poster editor's crop box.
 *
 * The box is fixed and 2:3, the shape of every poster tile ([ArtworkKind.POSTER]).
 * The *picture* moves and zooms behind it, the way a phone's photo cropper
 * works, so what is inside the box is always exactly what will be saved.
 *
 * [zoom] is relative to the smallest scale at which the picture still covers
 * the whole box (1 = "just covers"). [panX] and [panY] are how far the
 * picture's centre sits from the box's centre, in fractions of the box's own
 * width and height. Keeping both relative, not in screen pixels, is what lets
 * a framing survive the window changing size (unfolding, rotating) and a new
 * frame arriving at the same resolution.
 *
 * Every function takes the frame's size ([image], in source pixels) and the
 * box's size on screen ([box], in screen pixels); nothing here knows which
 * screen it is on.
 */
data class PosterFraming(val zoom: Float = 1f, val panX: Float = 0f, val panY: Float = 0f) {

    /** Screen pixels per source pixel. */
    fun scale(image: Dimensions, box: Dimensions): Float = coverScale(image, box) * zoom

    /** The picture's centre relative to the box's centre, in screen pixels. */
    fun offsetX(box: Dimensions): Float = panX * box.width
    fun offsetY(box: Dimensions): Float = panY * box.height

    /**
     * One step of a pinch or drag: zoom by [zoomBy] around [centroidX]/[centroidY]
     * (screen pixels from the box's centre), then move by [dx]/[dy] screen
     * pixels. The point under the fingers stays under the fingers, which is
     * what makes a pinch feel attached to the picture.
     */
    fun gesture(image: Dimensions, box: Dimensions, centroidX: Float, centroidY: Float, dx: Float, dy: Float, zoomBy: Float): PosterFraming {
        if (image.isEmpty || box.isEmpty) return this
        val newZoom = (zoom * zoomBy).coerceIn(1f, MAX_ZOOM)
        val ratio = newZoom / zoom
        val ox = centroidX - (centroidX - offsetX(box)) * ratio + dx
        val oy = centroidY - (centroidY - offsetY(box)) * ratio + dy
        return PosterFraming(newZoom, ox / box.width, oy / box.height).clamped(image, box)
    }

    /** The same framing, pulled back so the picture covers the box on every side. */
    fun clamped(image: Dimensions, box: Dimensions): PosterFraming {
        if (image.isEmpty || box.isEmpty) return this
        val z = zoom.coerceIn(1f, MAX_ZOOM)
        val s = coverScale(image, box) * z
        // How far the picture overhangs the box on each side; the centre may
        // travel that far and no further.
        val slackX = max(0f, (image.width * s - box.width) / 2f) / box.width
        val slackY = max(0f, (image.height * s - box.height) / 2f) / box.height
        return PosterFraming(z, panX.coerceIn(-slackX, slackX), panY.coerceIn(-slackY, slackY))
    }

    /** The source pixels inside the box: what Save cuts out of the full frame. */
    fun cropRect(image: Dimensions, box: Dimensions): CropRect {
        val c = clamped(image, box)
        val s = c.scale(image, box)
        val w = (box.width / s).coerceAtMost(image.width)
        val h = (box.height / s).coerceAtMost(image.height)
        val left = (image.width / 2f + (-box.width / 2f - c.offsetX(box)) / s).coerceIn(0f, image.width - w)
        val top = (image.height / 2f + (-box.height / 2f - c.offsetY(box)) / s).coerceIn(0f, image.height - h)
        return CropRect(left.roundToInt(), top.roundToInt(), w.roundToInt().coerceAtLeast(1), h.roundToInt().coerceAtLeast(1))
    }

    companion object {
        /** Width over height of every poster the app draws. */
        const val ASPECT = 2f / 3f

        /** Four times "just covers": past that a 1080p frame is a smear of a few hundred pixels. */
        const val MAX_ZOOM = 4f

        /**
         * The largest poster.jpg worth writing. Twice the 500x750 the tiles
         * are cached at, so a tile on the unfolded screen is still sharp, and
         * small enough to stay far under the 8 MB the artwork reader accepts.
         */
        const val MAX_OUTPUT_WIDTH = 1000
        const val MAX_OUTPUT_HEIGHT = 1500

        /** The scale at which [image] just covers [box]. */
        fun coverScale(image: Dimensions, box: Dimensions): Float =
            if (image.isEmpty || box.isEmpty) 1f else max(box.width / image.width, box.height / image.height)

        /** The biggest 2:3 box that fits in a [viewWidth] x [viewHeight] area. */
        fun boxIn(viewWidth: Float, viewHeight: Float): Dimensions {
            val h = min(viewHeight, viewWidth / ASPECT)
            return Dimensions(h * ASPECT, h)
        }

        /** The size to write [crop] at: scaled down to fit the maximum, never up. */
        fun outputSize(crop: CropRect): Pair<Int, Int> {
            val f = min(1f, min(MAX_OUTPUT_WIDTH.toFloat() / crop.width, MAX_OUTPUT_HEIGHT.toFloat() / crop.height))
            return (crop.width * f).roundToInt().coerceAtLeast(1) to (crop.height * f).roundToInt().coerceAtLeast(1)
        }
    }
}
