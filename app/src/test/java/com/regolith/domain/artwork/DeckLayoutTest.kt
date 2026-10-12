package com.regolith.domain.artwork

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** The fan a folder's deck is drawn as, on the poster (2:3) and the thumb (16:9). */
class DeckLayoutTest {
    private val twoByThree = 2f / 3f
    private fun shapes(n: Int, shape: Float = twoByThree) = List(n) { shape }
    private val poster = DeckLayout.cards(500f, 750f, shapes(5))

    @Test fun `five cards fan evenly either side of an upright front card, drawn back to front`() {
        assertEquals(listOf(-22f, 22f, -11f, 11f, 0f), poster.map { it.degrees })
        assertEquals(listOf(4, 3, 2, 1, 0), poster.map { it.rank })
        assertEquals(1f, poster.last().brightness)
    }

    @Test fun `the front card sits whole in the middle of the poster, shaped like one`() {
        val front = poster.last()
        assertEquals(250f, front.left + front.width / 2f, 0.01f)
        assertTrue(front.top > 0f && front.top + front.height < 750f)
        assertEquals(2f / 3f, front.width / front.height, 0.001f)
    }

    @Test fun `cards dim the further out they sit`() {
        val byAngle = poster.groupBy { abs(it.degrees) }.mapValues { (_, cards) -> cards.map { it.brightness }.distinct().single() }
        assertTrue(byAngle.getValue(0f) > byAngle.getValue(11f))
        assertTrue(byAngle.getValue(11f) > byAngle.getValue(22f))
    }

    @Test fun `every card turns about one point below the picture, so the fan opens upwards`() {
        assertEquals(1, poster.map { it.pivotX to it.pivotY }.distinct().size)
        assertTrue(poster.first().pivotY > 750f)
    }

    @Test fun `fewer pictures, fewer cards, still centred`() {
        val one = DeckLayout.cards(500f, 750f, shapes(1)).single()
        assertEquals(0f, one.degrees)
        assertEquals(0, one.rank)
        val two = DeckLayout.cards(500f, 750f, shapes(2))
        assertEquals(listOf(-5.5f, 5.5f), two.map { it.degrees })
        assertEquals("the right card lies on top", 0, two.last().rank)
        assertEquals(1f, two.last().brightness)
    }

    @Test fun `a hand holds five at most, and none for nothing`() {
        assertEquals(5, DeckLayout.cards(500f, 750f, shapes(9)).size)
        assertTrue(DeckLayout.cards(500f, 750f, shapes(0)).isEmpty())
        assertTrue(DeckLayout.cards(0f, 750f, shapes(3)).isEmpty())
    }

    @Test fun `a wide thumb holds taller cards, fanned wider, every card's middle inside it`() {
        val thumb = DeckLayout.cards(320f, 180f, shapes(5))
        assertEquals(144f, thumb.last().height, 0.01f)
        assertEquals(26f, thumb.maxOf { abs(it.degrees) })
        for (card in thumb) {
            val x = middleOf(card).first
            assertTrue("card ${card.rank} lands at x=$x", x in 0f..320f)
        }
    }

    @Test fun `a loose hand keeps every card whole inside its picture, with room for the shadows`() {
        val width = 1080f
        val height = width / DeckLayout.looseAspect
        val hand = DeckLayout.looseCards(width, height, shapes(5))
        val margin = hand.last().width * 0.15f
        for (corner in hand.flatMap { it.corners }) {
            assertTrue("corner $corner", corner.first in margin..width - margin && corner.second in margin..height - margin)
        }
        val front = hand.last()
        assertEquals(0f, front.degrees)
        assertEquals(width / 2f, front.left + front.width / 2f, 0.01f)
        assertTrue("a hand is wider than it is tall", DeckLayout.looseAspect > 1f)
    }

    @Test fun `a loose card on its own is the size of the front of five, in the middle`() {
        val five = DeckLayout.looseCards(1080f, 700f, shapes(5)).last()
        val one = DeckLayout.looseCards(1080f, 700f, shapes(1)).single()
        assertEquals(five.width, one.width, 0.01f)
        assertEquals(540f, one.left + one.width / 2f, 0.01f)
    }

    @Test fun `each card takes its own shape, with the area of a 2-by-3 one, all standing on one line`() {
        val wide = 16f / 9f
        val tall = 9f / 16f
        val hand = DeckLayout.cards(500f, 750f, listOf(tall, wide, twoByThree, wide, tall))
        val base = DeckLayout.cards(500f, 750f, shapes(5)).last()
        val front = hand.last()
        for (card in hand) {
            val shape = card.width / card.height
            assertTrue("rank ${card.rank} is ${card.width} x ${card.height}", shape in 0.5f..1.8f)
            assertEquals("all on one line", front.top + front.height, card.top + card.height, 0.5f)
        }
        assertEquals("the front card is the first shape: tall", tall, front.width / front.height, 0.01f)
        assertEquals("with a 2:3 card's area", base.width * base.height, front.width * front.height, base.width * base.height * 0.01f)
        assertTrue("taller than a 2:3 card", front.height > base.height)
        assertEquals("its middle where a 2:3 card's is", base.top + base.height / 2f, front.top + front.height / 2f, 0.5f)
        assertTrue("and whole on the poster", front.top > 0f && front.top + front.height < 750f)
    }

    @Test fun `a hand of wide cards stands in the middle of a tall poster, not at its foot`() {
        val base = DeckLayout.cards(500f, 750f, shapes(1)).single()
        val front = DeckLayout.cards(500f, 750f, shapes(5, 16f / 9f)).last()
        assertEquals(base.top + base.height / 2f, front.top + front.height / 2f, 0.5f)
    }

    @Test fun `a very wide card is kept inside most of the poster's width`() {
        val card = DeckLayout.cards(500f, 750f, listOf(2.39f)).single()
        assertTrue("${card.width}", card.width <= 400.5f)
        assertEquals(2.39f, card.width / card.height, 0.01f)
    }

    @Test fun `a loose hand of wide cards still fits its picture, and is made smaller to`() {
        val width = 1080f
        val height = width / DeckLayout.looseAspect
        val hand = DeckLayout.looseCards(width, height, shapes(5, 16f / 9f))
        for (corner in hand.flatMap { it.corners }) {
            assertTrue("corner $corner", corner.first in 0f..width && corner.second in 0f..height)
        }
        val standard = DeckLayout.looseCards(width, height, shapes(5)).last()
        assertTrue(hand.last().width * hand.last().height < standard.width * standard.height)
    }

    @Test fun `picks spread from the first video to the last`() {
        assertEquals(listOf(0, 6, 11, 17, 22), DeckLayout.picks(23))
        assertEquals(listOf(0, 1, 3, 4, 5), DeckLayout.picks(6))
        assertEquals(listOf(0, 1, 2), DeckLayout.picks(3))
        assertEquals(listOf(0), DeckLayout.picks(40, count = 1))
        assertTrue(DeckLayout.picks(0).isEmpty())
    }

    @Test fun `a deck is dealt from a folder's tall videos, spread through them, where it has any`() {
        val tall = 9f / 16f
        val wide = 16f / 9f
        // Wide films with phone clips among them: only the clips, first to last.
        val mixed = listOf(wide, tall, wide, tall, null, tall, wide, tall, wide, tall, tall, wide)
        assertEquals(listOf(1, 3, 7, 9, 10), DeckLayout.dealFrom(mixed))
        assertEquals("fewer clips than cards: every one", listOf(2, 4), DeckLayout.dealFrom(listOf(wide, wide, tall, wide, tall)))
        assertEquals("none tall: the wide ones, spread", listOf(0, 1, 3, 4, 5), DeckLayout.dealFrom(listOf(wide, wide, wide, null, wide, 1f)))
        assertEquals("square is not tall", listOf(1), DeckLayout.dealFrom(listOf(1f, 0.75f)))
        assertTrue(DeckLayout.dealFrom(emptyList()).isEmpty())
    }

    @Test fun `frames are dealt a video at a time, so cards side by side differ`() {
        assertEquals(listOf("a1", "b1", "a2", "b2", "a3"), DeckLayout.deal(listOf(listOf("a1", "a2", "a3"), listOf("b1", "b2"))))
        assertEquals(listOf("b1"), DeckLayout.deal(listOf(emptyList(), listOf("b1"))))
        assertTrue(DeckLayout.deal(emptyList<List<String>>()).isEmpty())
    }

    /** Where a card's middle lands once it has turned about the hand (clockwise, y down, as a canvas turns). */
    private fun middleOf(card: DeckLayout.Card): Pair<Float, Float> {
        val turn = Math.toRadians(card.degrees.toDouble())
        val dx = card.left + card.width / 2f - card.pivotX
        val dy = card.top + card.height / 2f - card.pivotY
        return (card.pivotX + dx * cos(turn) - dy * sin(turn)).toFloat() to (card.pivotY + dx * sin(turn) + dy * cos(turn)).toFloat()
    }

    // ---- The pile, for a tile on the Library's wall (112 × 168 on a phone).

    private fun bounds(cards: List<DeckLayout.Card>): FloatArray {
        val corners = cards.flatMap { it.corners }
        return floatArrayOf(corners.minOf { it.first }, corners.minOf { it.second }, corners.maxOf { it.first }, corners.maxOf { it.second })
    }

    @Test fun `a tall pile is three prints, drawn back to front, the front one at full light`() {
        val pile = DeckLayout.pileCards(DeckShape.TALL, 112f, 168f)
        assertEquals(listOf(2, 1, 0), pile.map { it.rank })
        assertEquals(1f, pile.last().brightness)
        assertTrue(pile.first().brightness < pile[1].brightness)
    }

    @Test fun `a wide pile is four prints, climbing the tile from its front card at the bottom`() {
        val pile = DeckLayout.pileCards(DeckShape.WIDE, 112f, 168f)
        assertEquals(listOf(3, 2, 1, 0), pile.map { it.rank })
        val middles = pile.sortedBy { it.rank }.map { it.top + it.height / 2f }
        assertEquals("each print above the one before", middles.sortedDescending(), middles)
        assertTrue("the front print near the bottom", middles.first() > 168f * 0.75f)
    }

    @Test fun `either pile stands as tall as a poster, inside its tile but for a little at the sides`() {
        for (shape in DeckShape.entries) {
            val (left, top, right, bottom) = bounds(DeckLayout.pileCards(shape, 112f, 168f)).toList()
            assertTrue("$shape: ${bottom - top} of 168 tall", bottom - top > 168f * 0.97f)
            assertTrue("$shape: top $top", top >= 0f)
            assertTrue("$shape: bottom $bottom", bottom <= 168f)
            assertTrue("$shape: reaches $left and $right", left >= -112f * 0.03f && right <= 112f * 1.03f)
        }
    }

    @Test fun `every card is cut to the pile's shape, with a 2-3 card's area`() {
        for (shape in DeckShape.entries) {
            val pile = DeckLayout.pileCards(shape, 112f, 168f)
            for (card in pile) assertEquals(shape.aspect, card.width / card.height, 0.001f)
            assertEquals(1, pile.map { (it.width * it.height).toInt() }.distinct().size)
        }
    }

    @Test fun `the front card is where the tile looks for it however many cards there are`() {
        for (shape in DeckShape.entries) {
            val whole = DeckLayout.pileCards(shape, 112f, 168f).single { it.rank == 0 }
            assertEquals(whole, DeckLayout.pileFront(shape, 112f, 168f))
            assertEquals(whole, DeckLayout.pileCards(shape, 112f, 168f, count = 2).single { it.rank == 0 })
        }
    }

    @Test fun `a pile scales with its tile`() {
        val small = DeckLayout.pileFront(DeckShape.TALL, 112f, 168f)
        val big = DeckLayout.pileFront(DeckShape.TALL, 224f, 336f)
        assertEquals(small.left * 2f, big.left, 0.01f)
        assertEquals(small.width * 2f, big.width, 0.01f)
        assertEquals(small.degrees, big.degrees)
    }

    @Test fun `a pile's shape is its front card's, and reads back by name`() {
        assertEquals(DeckShape.TALL, DeckShape.of(9f / 16f))
        assertEquals(DeckShape.WIDE, DeckShape.of(16f / 9f))
        assertEquals(DeckShape.WIDE, DeckShape.of(1f))
        assertEquals(DeckShape.WIDE, DeckShape.named("WIDE"))
        assertEquals(null, DeckShape.named(null))
        assertEquals(null, DeckShape.named("ROUND"))
    }
}
