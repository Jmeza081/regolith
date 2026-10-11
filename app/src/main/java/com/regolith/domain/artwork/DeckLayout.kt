package com.regolith.domain.artwork

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Where the cards go on a folder's deck: the picture a folder wears when it
 * has none of its own, up to [MAX_CARDS] frames of what is inside, fanned
 * like a hand of cards from a point below the picture. Pure geometry, so the
 * fan can be tested without drawing it; `DeckPainter` draws it.
 *
 * The card in the middle is the front card, upright and whole. The rest
 * turn out to either side behind it, each a little dimmer, and the outer
 * ones run off the edges, which reads as more cards than are drawn.
 *
 * A deck is dealt from a folder's tall videos (a phone's clips) where it has
 * any ([dealFrom]), so its cards are tall; a folder of wide videos only deals
 * wide cards. Each card has its own video's shape, a film's as wide as its
 * picture. A tall picture (the 2:3 poster) holds cards a little over half its
 * height; a wide one (the 16:9 thumb and backdrop) holds taller cards spread
 * wider, so a hand still fills a short, wide frame.
 *
 * A collection's page has room for the whole hand, so there it is laid
 * loose ([looseCards]): every card whole, with no box round them.
 */
object DeckLayout {
    /** The most cards a deck holds: enough to read as a handful, and five frames to grab. */
    const val MAX_CARDS = 5

    /**
     * One card, upright at [left], [top], [width] × [height], then turned
     * [degrees] clockwise about ([pivotX], [pivotY]): the hand holding the
     * cards, below the picture, so the fan opens upwards.
     */
    data class Card(
        /** Which frame it shows: 0 is the front card, the first one picked; higher is further back. */
        val rank: Int,
        val left: Float,
        val top: Float,
        val width: Float,
        val height: Float,
        val pivotX: Float,
        val pivotY: Float,
        val degrees: Float,
        /** 1 for the front card, dimmer the further from the middle a card sits. */
        val brightness: Float,
    ) {
        /** Where its four corners land once it has turned (clockwise, y down, as a canvas turns). */
        val corners: List<Pair<Float, Float>>
            get() {
                val turn = Math.toRadians(degrees.toDouble())
                val cos = cos(turn).toFloat()
                val sin = sin(turn).toFloat()
                return listOf(left to top, left + width to top, left to top + height, left + width to top + height).map { (x, y) ->
                    val dx = x - pivotX
                    val dy = y - pivotY
                    (pivotX + dx * cos - dy * sin) to (pivotY + dx * sin + dy * cos)
                }
            }
    }

    /**
     * The cards for a [width] × [height] picture, one per entry of [shapes]:
     * each card's own width over height, its video's (a phone clip tall, a
     * film wide), front card first, at most [MAX_CARDS]. In the order they are
     * drawn: back to front, so the last is the front card. Of two cards
     * equally far out, the left one is drawn first and the right one lies on
     * top of it.
     *
     * Every card has the area a 2:3 card of this deck would have, so a wide
     * one is not dwarfed by a tall one, and none is wider than most of the
     * picture or taller than nearly all of it. They all stand on one line, as
     * cards held in one hand do: a tall card reaches higher, a wide one less.
     * The front card's middle is where a 2:3 card's would be.
     */
    fun cards(width: Float, height: Float, shapes: List<Float>): List<Card> {
        if (width <= 0f || height <= 0f) return emptyList()
        val tall = height > width
        val baseHeight = height * if (tall) TALL_CARD else WIDE_CARD
        val sizes = shapes.take(MAX_CARDS).map {
            size(it, baseHeight * CARD_ASPECT, maxWidth = width * if (tall) TALL_MAX_WIDTH else WIDE_MAX_WIDTH, maxHeight = height * MAX_HEIGHT)
        }
        // The front card's middle where a 2:3 card's would be, so a hand of
        // wide cards does not sink to the bottom of a tall poster; the rest
        // stand on the line it stands on.
        val middle = height * (if (tall) TALL_TOP else WIDE_TOP) + baseHeight / 2f
        val baseline = middle + (sizes.firstOrNull()?.second ?: baseHeight) / 2f
        return fan(sizes, width / 2f, baseline, baseHeight, if (tall) TALL_STEP_DEGREES else WIDE_STEP_DEGREES)
    }

    /**
     * The same hand laid loose, for a collection's page: every card whole
     * inside a [width] × [height] picture ([looseAspect] is the shape it is
     * made for), with room round the hand for the shadows, and the hand in
     * the middle of it. Cards are never bigger than a full hand of 2:3 cards
     * would have them, so one card on its own is no bigger than the front of
     * five; a hand of wide cards, which spreads further, is made smaller.
     */
    fun looseCards(width: Float, height: Float, shapes: List<Float>): List<Card> {
        if (width <= 0f || height <= 0f || shapes.isEmpty()) return emptyList()
        val unit = unitHand(shapes)
        val box = bounds(unit)
        val scale = minOf(width / looseBox.width, height / looseBox.height, width / box.width, height / box.height)
        val dx = (width - box.width * scale) / 2f - box.left * scale
        val dy = (height - box.height * scale) / 2f - box.top * scale
        return unit.map {
            it.copy(
                left = it.left * scale + dx,
                top = it.top * scale + dy,
                width = it.width * scale,
                height = it.height * scale,
                pivotX = it.pivotX * scale + dx,
                pivotY = it.pivotY * scale + dy,
            )
        }
    }

    /**
     * Width over height of a loose hand's picture: a full hand of 2:3 cards
     * and the room for its shadows. A page sizes its room for the deck by it,
     * and the picture is made at it.
     */
    val looseAspect: Float get() = looseBox.width / looseBox.height

    /**
     * Cards of [sizes] (width, height; front card first) standing on
     * [baseline] about [centerX], turned about a point below it: the hand
     * holding them, as far below the baseline as nine tenths of a 2:3 card
     * ([baseHeight]) is high, so the fan opens gently. In draw order.
     */
    private fun fan(sizes: List<Pair<Float, Float>>, centerX: Float, baseline: Float, baseHeight: Float, step: Float): List<Card> {
        val n = sizes.size
        if (n == 0) return emptyList()
        val pivotY = baseline + baseHeight * (PIVOT - 1f)
        val middle = (n - 1) / 2f
        val drawOrder = (0 until n).sortedWith(compareByDescending<Int> { abs(it - middle) }.thenBy { it })
        return drawOrder.mapIndexed { drawn, slot ->
            val offset = slot - middle
            val rank = n - 1 - drawn
            val (cardWidth, cardHeight) = sizes[rank]
            Card(
                rank = rank,
                left = centerX - cardWidth / 2f,
                top = baseline - cardHeight,
                width = cardWidth,
                height = cardHeight,
                pivotX = centerX,
                pivotY = pivotY,
                degrees = offset * step,
                brightness = if (rank == 0) 1f else 1f - DIM_PER_STEP * abs(offset),
            )
        }
    }

    /**
     * A card of [shape] (width over height) with the area of a 2:3 card
     * [baseWidth] wide, made smaller if need be to fit [maxWidth] × [maxHeight].
     * A shape that makes no sense is taken as 2:3.
     */
    private fun size(shape: Float, baseWidth: Float, maxWidth: Float, maxHeight: Float): Pair<Float, Float> {
        val aspect = shape.takeIf { it.isFinite() && it > 0f } ?: CARD_ASPECT
        val area = baseWidth * baseWidth / CARD_ASPECT
        val width = sqrt(area * aspect)
        val height = width / aspect
        val fit = minOf(1f, maxWidth / width, maxHeight / height)
        return width * fit to height * fit
    }

    /** A hand at unit size, for laying loose: a 2:3 card is 1 wide, and its top is at 0. */
    private fun unitHand(shapes: List<Float>): List<Card> {
        val baseHeight = 1f / CARD_ASPECT
        val sizes = shapes.take(MAX_CARDS).map { size(it, 1f, maxWidth = LOOSE_MAX_WIDTH, maxHeight = LOOSE_MAX_HEIGHT) }
        return fan(sizes, 0f, baseHeight, baseHeight, TALL_STEP_DEGREES)
    }

    /** A hand's extent: its cards' turned corners, and the room round them for the shadows. */
    private class Box(val left: Float, val top: Float, val width: Float, val height: Float)

    private fun bounds(cards: List<Card>): Box {
        val corners = cards.flatMap { it.corners }
        val left = corners.minOf { it.first } - LOOSE_SIDE
        val top = corners.minOf { it.second } - LOOSE_ABOVE
        return Box(left, top, corners.maxOf { it.first } + LOOSE_SIDE - left, corners.maxOf { it.second } + LOOSE_BELOW - top)
    }

    /** A full hand of 2:3 cards, measured once: the room a page makes for a deck. */
    private val looseBox: Box by lazy { bounds(unitHand(List(MAX_CARDS) { CARD_ASPECT })) }

    /**
     * Which of [size] videos, in the folder's own order, the deck shows:
     * [count] spread evenly from the first to the last, rather than the
     * first [count]. A folder of phone clips is named by the time they were
     * taken, and its first five are usually five shots of one afternoon.
     */
    fun picks(size: Int, count: Int = MAX_CARDS): List<Int> {
        if (size <= 0 || count <= 0) return emptyList()
        if (size <= count) return List(size) { it }
        if (count == 1) return listOf(0)
        // Rounded to the nearest index, in whole numbers.
        return List(count) { i -> ((i.toLong() * (size - 1) + (count - 1) / 2) / (count - 1)).toInt() }
    }

    /**
     * Which of a folder's videos, in its own order, the deck is dealt from,
     * given each one's shape as shown (width over height, null when it has
     * not been measured): its tall ones where it has any, so every card is
     * tall, and all of them where it has none. [count] at most, spread from
     * the first to the last as [picks] spreads them.
     */
    fun dealFrom(shapes: List<Float?>, count: Int = MAX_CARDS): List<Int> {
        val tall = shapes.indices.filter { shapes[it]?.let { shape -> shape < 1f } == true }
        val pool = tall.ifEmpty { shapes.indices.toList() }
        return picks(pool.size, count).map { pool[it] }
    }

    /**
     * Deal [hands] (each video's frames, when one video gives several) into
     * one run, a frame from each video in turn: `[[a1, a2, a3], [b1, b2]]`
     * becomes `a1, b1, a2, b2, a3`. In rank order, so cards next to each
     * other in the fan come from different videos where there are any.
     */
    fun <T> deal(hands: List<List<T>>): List<T> {
        val out = mutableListOf<T>()
        val deepest = hands.maxOfOrNull { it.size } ?: 0
        for (round in 0 until deepest) for (hand in hands) hand.getOrNull(round)?.let { out += it }
        return out
    }

    /** The standard card, a poster's 2:3: every card has its area, whatever its own shape. */
    private const val CARD_ASPECT = 2f / 3f

    /** How far below a 2:3 card's top the hand is, in its heights: far enough that the fan opens gently. */
    private const val PIVOT = 1.9f

    /** On a tall picture: a 2:3 card's height, and where its top is, as parts of the picture's height. */
    private const val TALL_CARD = 0.56f
    private const val TALL_TOP = 0.25f
    private const val TALL_STEP_DEGREES = 11f

    /** On a wide picture: taller cards, nearer the top, fanned wider to fill the width (2:3 again). */
    private const val WIDE_CARD = 0.8f
    private const val WIDE_TOP = 0.1f
    private const val WIDE_STEP_DEGREES = 13f

    /** The widest a card may be, as a part of the picture's width (tall, wide); and the tallest, of its height. */
    private const val TALL_MAX_WIDTH = 0.8f
    private const val WIDE_MAX_WIDTH = 0.6f
    private const val MAX_HEIGHT = 0.9f

    /** The same for a hand laid loose, in widths of a 2:3 card: room for a film's 2.39:1 and a phone's 9:16. */
    private const val LOOSE_MAX_WIDTH = 2f
    private const val LOOSE_MAX_HEIGHT = 2.2f

    /** How much darker each step out from the middle is. */
    private const val DIM_PER_STEP = 0.15f

    /**
     * The room round a loose hand, in card widths, for the shadows `DeckPainter`
     * draws: they spread about a quarter of a card's width and fall a little
     * below, so a picture cut tighter would show their edge as a hard line.
     */
    private const val LOOSE_SIDE = 0.26f
    private const val LOOSE_ABOVE = 0.18f
    private const val LOOSE_BELOW = 0.34f
}
