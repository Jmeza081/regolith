package com.regolith.domain.artwork

import kotlin.math.abs

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
 * A tall picture (the 2:3 poster) holds cards a little over half its height;
 * a wide one (the 16:9 thumb and backdrop) holds taller cards spread wider,
 * so a hand still fills a short, wide frame.
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
    )

    /**
     * [count] cards (at most [MAX_CARDS]) for a [width] × [height] picture,
     * in the order they are drawn: back to front, so the last is the front
     * card. Of two cards equally far out, the left one is drawn first and
     * the right one lies on top of it.
     */
    fun cards(width: Float, height: Float, count: Int): List<Card> {
        val n = count.coerceAtMost(MAX_CARDS)
        if (n <= 0 || width <= 0f || height <= 0f) return emptyList()
        val tall = height > width
        val cardHeight = height * if (tall) TALL_CARD else WIDE_CARD
        val cardWidth = cardHeight * CARD_ASPECT
        val left = (width - cardWidth) / 2f
        val top = height * if (tall) TALL_TOP else WIDE_TOP
        val pivotY = top + cardHeight * PIVOT
        val step = if (tall) TALL_STEP_DEGREES else WIDE_STEP_DEGREES
        val middle = (n - 1) / 2f
        val drawOrder = (0 until n).sortedWith(compareByDescending<Int> { abs(it - middle) }.thenBy { it })
        return drawOrder.mapIndexed { drawn, slot ->
            val offset = slot - middle
            val front = drawn == n - 1
            Card(
                rank = n - 1 - drawn,
                left = left,
                top = top,
                width = cardWidth,
                height = cardHeight,
                pivotX = width / 2f,
                pivotY = pivotY,
                degrees = offset * step,
                brightness = if (front) 1f else 1f - DIM_PER_STEP * abs(offset),
            )
        }
    }

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

    /** A card is a poster: 2:3. */
    private const val CARD_ASPECT = 2f / 3f

    /** How far below the card's top the hand is, in card heights: far enough that the fan opens gently. */
    private const val PIVOT = 1.9f

    /** On a tall picture: the card's height, and where its top is, as parts of the picture's height. */
    private const val TALL_CARD = 0.56f
    private const val TALL_TOP = 0.25f
    private const val TALL_STEP_DEGREES = 11f

    /** On a wide picture: taller cards, nearer the top, fanned wider to fill the width. */
    private const val WIDE_CARD = 0.8f
    private const val WIDE_TOP = 0.1f
    private const val WIDE_STEP_DEGREES = 13f

    /** How much darker each step out from the middle is. */
    private const val DIM_PER_STEP = 0.15f
}
