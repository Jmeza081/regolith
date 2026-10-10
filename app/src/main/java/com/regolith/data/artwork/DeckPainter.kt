package com.regolith.data.artwork

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import androidx.core.graphics.withRotation
import com.regolith.domain.artwork.BoxBlur
import com.regolith.domain.artwork.DeckLayout
import kotlin.math.roundToInt

/**
 * Draws a folder's deck ([DeckLayout]): its frames dealt as cards, fanned
 * over a blurred, darkened copy of the front card, into one bitmap the
 * size of the picture being made. The picture a folder wears when it has
 * none of its own, written once and cached like any other.
 *
 * Software drawing on purpose: this runs off the main thread into a bitmap
 * that becomes a JPEG, and a software canvas is the one that draws
 * `setShadowLayer` round any shape (a hardware one only does it for text).
 */
object DeckPainter {
    /**
     * [frames] as a [width] × [height] deck: the first is the front card and
     * colours the background, and the rest go behind it in [DeckLayout]'s
     * rank order. At most [DeckLayout.MAX_CARDS] are used; there must be one.
     */
    fun paint(frames: List<Bitmap>, width: Int, height: Int): Bitmap {
        require(frames.isNotEmpty()) { "a deck needs a card" }
        val out = createBitmap(width, height)
        val canvas = Canvas(out)
        canvas.drawColor(GROUND)
        background(canvas, frames.first(), width, height)
        for (card in DeckLayout.cards(width.toFloat(), height.toFloat(), frames.size)) {
            card(canvas, frames[card.rank], card)
        }
        return out
    }

    /**
     * The same cards laid loose ([DeckLayout.looseCards]): every card whole,
     * on nothing — transparent round them but for their shadows — for a
     * collection's page to float over its own light. [width] across, as tall
     * as [DeckLayout.looseAspect] makes it.
     */
    fun paintLoose(frames: List<Bitmap>, width: Int): Bitmap {
        require(frames.isNotEmpty()) { "a deck needs a card" }
        val height = (width / DeckLayout.looseAspect).roundToInt()
        // A new bitmap starts transparent, which is the whole of the ground.
        val out = createBitmap(width, height)
        val canvas = Canvas(out)
        for (card in DeckLayout.looseCards(width.toFloat(), height.toFloat(), frames.size)) {
            card(canvas, frames[card.rank], card)
        }
        return out
    }

    /**
     * The front card again, blurred to a wash of its colours, more
     * saturated and darker, then darker still towards the bottom, where the
     * tile's own chips and the cards' shadows sit.
     *
     * The blur is cheap because the copy is tiny: a twentieth of the
     * picture, blurred, then scaled back up, which smooths it the rest of
     * the way.
     */
    private fun background(canvas: Canvas, front: Bitmap, width: Int, height: Int) {
        val w = (width / BACKGROUND_SHRINK).coerceAtLeast(MIN_BACKGROUND)
        val h = (height / BACKGROUND_SHRINK).coerceAtLeast(MIN_BACKGROUND)
        val small = shrink(front, w, h)
        val pixels = IntArray(w * h).also { small.getPixels(it, 0, w, 0, 0, w, h) }
        if (small !== front) small.recycle()
        val blurred = Bitmap.createBitmap(BoxBlur.blur(pixels, w, h, BLUR_RADIUS), w, h, Bitmap.Config.ARGB_8888)
        try {
            val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = BACKGROUND_TONE }
            canvas.drawBitmap(blurred, null, RectF(0f, 0f, width.toFloat(), height.toFloat()), paint)
        } finally {
            blurred.recycle()
        }
        val shade = Paint().apply {
            shader = LinearGradient(
                0f, 0f, 0f, height.toFloat(),
                intArrayOf(ground(0.1f), ground(0.45f), ground(0.92f)),
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), shade)
    }

    /**
     * One card: [frame] cropped to the card's shape, in a rounded box with
     * a soft shadow and a thin light edge, dimmed by its
     * [DeckLayout.Card.brightness] and turned about the hand below.
     */
    private fun card(canvas: Canvas, frame: Bitmap, card: DeckLayout.Card) {
        val face = shrink(frame, card.width.roundToInt(), card.height.roundToInt())
        val front = card.rank == 0
        val rect = RectF(card.left, card.top, card.left + face.width, card.top + face.height)
        val radius = card.width * CORNER
        try {
            canvas.withRotation(card.degrees, card.pivotX, card.pivotY) {
                // The shadow: like CSS box-shadow, drawn by a shape the face
                // then covers. Its own colour is the ground, so the sliver an
                // anti-aliased edge lets through reads as edge, not as a halo.
                val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = GROUND
                    setShadowLayer(card.width * SHADOW_BLUR, 0f, card.width * SHADOW_DROP, if (front) FRONT_SHADOW else SHADOW)
                }
                drawRoundRect(rect, radius, radius, shadow)
                // The face, through a shader so the rounded corners are
                // anti-aliased (clipping to a path is not, in software).
                // Linear filtering asked for outright, as the colour bleed
                // found a BitmapShader can ignore the paint's own flag.
                val shader = BitmapShader(face, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
                    filterMode = BitmapShader.FILTER_MODE_LINEAR
                    setLocalMatrix(Matrix().apply { setTranslate(card.left, card.top) })
                }
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    this.shader = shader
                    if (card.brightness < 1f) colorFilter = dimmed(card.brightness)
                }
                drawRoundRect(rect, radius, radius, paint)
                val stroke = card.width * EDGE
                val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    strokeWidth = stroke
                    color = if (front) FRONT_EDGE else EDGE_COLOR
                }
                val inset = stroke / 2f
                drawRoundRect(RectF(rect).apply { inset(inset, inset) }, radius - inset, radius - inset, edge)
            }
        } finally {
            if (face !== frame) face.recycle()
        }
    }

    /**
     * [src] centre-cropped to [w] × [h], averaged down rather than sampled.
     * One big scaling step reads only a few source pixels per result pixel,
     * so a stray highlight could tint a whole background and fine detail
     * turns to grit on a thumb's small card; halving a step at a time
     * averages every pixel in.
     */
    private fun shrink(src: Bitmap, w: Int, h: Int): Bitmap {
        var current = src
        while (current.width >= w * 2 && current.height >= h * 2) {
            val half = current.scale(current.width / 2, current.height / 2)
            if (current !== src) current.recycle()
            current = half
        }
        val cropped = ArtworkStore.centerCrop(current, w, h)
        if (current !== src && current !== cropped) current.recycle()
        return cropped
    }

    private fun dimmed(brightness: Float) =
        ColorMatrixColorFilter(ColorMatrix().apply { setScale(brightness, brightness, brightness, 1f) })

    /** The app's ground (#0A0A0A) at [alpha]. */
    private fun ground(alpha: Float) = Color.argb((alpha * 255).roundToInt(), 10, 10, 10)

    private val GROUND = Color.rgb(10, 10, 10)

    /** More colour, less light: a blur averages most pictures towards grey. */
    private val BACKGROUND_TONE = ColorMatrixColorFilter(
        ColorMatrix().apply {
            setSaturation(1.4f)
            postConcat(ColorMatrix().apply { setScale(0.6f, 0.6f, 0.6f, 1f) })
        },
    )

    private const val BACKGROUND_SHRINK = 20
    private const val MIN_BACKGROUND = 4
    private const val BLUR_RADIUS = 2

    /** Sizes in parts of the card's width, so every kind of picture is the same deck at its own scale. */
    private const val CORNER = 0.065f
    private const val EDGE = 0.009f
    private const val SHADOW_BLUR = 0.17f
    private const val SHADOW_DROP = 0.083f

    private val SHADOW = Color.argb(153, 0, 0, 0)
    private val FRONT_SHADOW = Color.argb(179, 0, 0, 0)
    private val EDGE_COLOR = Color.argb(66, 255, 255, 255)
    private val FRONT_EDGE = Color.argb(82, 255, 255, 255)
}
