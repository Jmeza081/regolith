package com.regolith.data.artwork

import android.graphics.Bitmap
import android.graphics.Color
import androidx.core.graphics.createBitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.regolith.domain.artwork.DeckLayout
import com.regolith.domain.artwork.DeckShape
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.cos
import kotlin.math.sin

/**
 * The deck as pixels, drawn for real (Robolectric's native graphics rather
 * than its stand-in canvas, which draws nothing), so a card that lands in
 * the wrong place or a background in the wrong colour fails here and not
 * on a phone.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DeckPainterTest {
    /** A frame of one colour, 2:3 unless said otherwise, so its card is the deck's standard one. */
    private fun frame(color: Int, width: Int = 400, height: Int = 600): Bitmap = createBitmap(width, height).apply { eraseColor(color) }

    private val twoByThree = List(5) { 2f / 3f }

    /** The pixel under a point on [card], given in parts of its width and height, once the card has turned. */
    private fun Bitmap.on(card: DeckLayout.Card, across: Float, down: Float): Int {
        val turn = Math.toRadians(card.degrees.toDouble())
        val dx = card.left + card.width * across - card.pivotX
        val dy = card.top + card.height * down - card.pivotY
        val x = card.pivotX + dx * cos(turn) - dy * sin(turn)
        val y = card.pivotY + dx * sin(turn) + dy * cos(turn)
        return getPixel(x.toInt(), y.toInt())
    }

    @Test fun `the front card is the first frame, whole and at full brightness, in the middle`() {
        val frames = listOf(frame(Color.RED)) + List(4) { frame(Color.BLUE) }
        val deck = DeckPainter.paint(frames, 500, 750)
        assertEquals(500, deck.width)
        assertEquals(750, deck.height)
        val front = DeckLayout.cards(500f, 750f, twoByThree).last()
        assertEquals(Color.RED, deck.on(front, 0.5f, 0.5f))
    }

    @Test fun `a card further back shows its own frame, dimmed`() {
        val frames = listOf(Color.RED, Color.RED, Color.BLUE, Color.RED, Color.RED).map(::frame)
        val deck = DeckPainter.paint(frames, 500, 750)
        // The third frame is the left one of the middle pair, out of the
        // front card's way (and out of its shadow) near its own left edge.
        val third = DeckLayout.cards(500f, 750f, twoByThree).single { it.rank == 2 }
        val pixel = deck.on(third, 0.1f, 0.35f)
        assertTrue("got ${Integer.toHexString(pixel)}", Color.red(pixel) < 20 && Color.green(pixel) < 20)
        assertTrue("got ${Color.blue(pixel)}", Color.blue(pixel) in 190..240)
    }

    @Test fun `the background is the front frame's colour, darkened, and darker towards the bottom`() {
        val deck = DeckPainter.paint(listOf(frame(Color.RED)), 500, 750)
        val top = deck.getPixel(4, 4)
        assertTrue("got ${Integer.toHexString(top)}", Color.red(top) in 100..200 && Color.green(top) < 30 && Color.blue(top) < 30)
        assertTrue(Color.red(deck.getPixel(4, 745)) < Color.red(top))
    }

    @Test fun `laid loose, the cards stand on nothing`() {
        val deck = DeckPainter.paintLoose(listOf(frame(Color.RED)) + List(4) { frame(Color.BLUE) }, 1080)
        assertEquals(1080, deck.width)
        assertEquals((1080 / DeckLayout.looseAspect).toInt().toFloat(), deck.height.toFloat(), 1f)
        assertEquals("the corners are clear", 0, Color.alpha(deck.getPixel(2, 2)))
        assertEquals(0, Color.alpha(deck.getPixel(deck.width - 3, 2)))
        val front = DeckLayout.looseCards(deck.width.toFloat(), deck.height.toFloat(), twoByThree).last()
        assertEquals(Color.RED, deck.on(front, 0.5f, 0.5f))
    }

    @Test fun `a wide frame makes a wide card, not a tall crop of one`() {
        val deck = DeckPainter.paint(listOf(frame(Color.RED, 800, 450)), 500, 750)
        val card = DeckLayout.cards(500f, 750f, listOf(16f / 9f)).single()
        assertEquals(Color.RED, deck.on(card, 0.5f, 0.5f))
        // Where a 2:3 card's top would be, there is only the background.
        val standard = DeckLayout.cards(500f, 750f, listOf(2f / 3f)).single()
        assertTrue(deck.on(standard, 0.5f, 0.05f) != Color.RED)
    }

    @Test fun `black bars baked into a film are left off its card`() {
        // A 2.39:1 picture in a 16:9 frame: 57 px of black above and below.
        val letterboxed = frame(Color.BLACK, 800, 450).also { bitmap ->
            for (y in 57 until 393) for (x in 0 until 800) bitmap.setPixel(x, y, Color.RED)
        }
        val deck = DeckPainter.paint(listOf(letterboxed), 500, 750)
        // Down the middle of the card: the picture, and just past either end
        // of it the card's edge and the background, never a black bar.
        val column = (0 until 750).map { deck.getPixel(250, it) }
        val first = column.indexOfFirst { it == Color.RED }
        val last = column.indexOfLast { it == Color.RED }
        assertTrue("a wide card's worth of picture: ${last - first} px", last - first in 140..175)
        for (y in listOf(first - 3, last + 3)) {
            assertTrue("no bar at $y: ${Integer.toHexString(column[y])}", Color.red(column[y]) > 30)
        }
    }

    @Test fun `a thumb deals the same hand at its own shape`() {
        val deck = DeckPainter.paint(listOf(frame(Color.GREEN)), 320, 180)
        assertEquals(320, deck.width)
        val front = DeckLayout.cards(320f, 180f, listOf(2f / 3f)).single()
        assertEquals(Color.GREEN, deck.on(front, 0.5f, 0.5f))
    }

    /** The pixel under a point on [card] of a pile laid out for a [tile]-wide tile, in the pile's picture, which reaches past the tile. */
    private fun Bitmap.onPile(card: DeckLayout.Card, tile: Int, across: Float, down: Float): Int {
        val turn = Math.toRadians(card.degrees.toDouble())
        val dx = card.left + card.width * across - card.pivotX
        val dy = card.top + card.height * down - card.pivotY
        val x = card.pivotX + dx * cos(turn) - dy * sin(turn) + tile * DeckLayout.PILE_SIDE
        val y = card.pivotY + dx * sin(turn) + dy * cos(turn) + tile * DeckLayout.PILE_ABOVE
        return getPixel(x.toInt(), y.toInt())
    }

    @Test fun `a pile's picture reaches past its tile, with room for the shadows`() {
        val pile = DeckPainter.paintPile(listOf(frame(Color.RED, 360, 640)), 500)
        assertEquals((500 * (1 + 2 * DeckLayout.PILE_SIDE)).toInt(), pile.bitmap.width)
        assertEquals((750 + 500 * (DeckLayout.PILE_ABOVE + DeckLayout.PILE_BELOW)).toInt(), pile.bitmap.height)
    }

    @Test fun `a pile stands on nothing`() {
        val pile = DeckPainter.paintPile(List(3) { frame(Color.RED, 360, 640) }, 500).bitmap
        for ((x, y) in listOf(0 to 0, pile.width - 1 to 0, 0 to pile.height - 1, pile.width - 1 to pile.height - 1)) {
            assertEquals("corner $x,$y", 0, Color.alpha(pile.getPixel(x, y)))
        }
    }

    @Test fun `tall frames make a tall pile, its front frame where the tile looks for the front card`() {
        val frames = listOf(frame(Color.RED, 360, 640)) + List(2) { frame(Color.BLUE, 360, 640) }
        val pile = DeckPainter.paintPile(frames, 500)
        assertEquals(DeckShape.TALL, pile.shape)
        val front = DeckLayout.pileFront(DeckShape.TALL, 500f, 750f)
        assertEquals(Color.RED, pile.bitmap.onPile(front, 500, 0.5f, 0.5f))
        assertEquals(Color.RED, pile.bitmap.onPile(front, 500, 0.15f, 0.9f))
    }

    @Test fun `wide frames make a wide pile, each print showing its own frame`() {
        val frames = listOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW).map { frame(it, 640, 360) }
        val pile = DeckPainter.paintPile(frames, 500)
        assertEquals(DeckShape.WIDE, pile.shape)
        val cards = DeckLayout.pileCards(DeckShape.WIDE, 500f, 750f)
        assertEquals(Color.RED, pile.bitmap.onPile(cards.single { it.rank == 0 }, 500, 0.5f, 0.5f))
        // The top of each print behind shows above the one in front of it, dimmed.
        val top = pile.bitmap.onPile(cards.single { it.rank == 3 }, 500, 0.5f, 0.2f)
        assertTrue("got ${Integer.toHexString(top)}", Color.red(top) > 90 && Color.green(top) > 90 && Color.blue(top) < 40)
    }

    @Test fun `a pile with fewer frames than it holds keeps its front card in its place`() {
        val pile = DeckPainter.paintPile(listOf(frame(Color.GREEN, 640, 360)), 500)
        val front = DeckLayout.pileFront(DeckShape.WIDE, 500f, 750f)
        assertEquals(Color.GREEN, pile.bitmap.onPile(front, 500, 0.5f, 0.5f))
        // Nothing where the prints behind would be.
        val behind = DeckLayout.pileCards(DeckShape.WIDE, 500f, 750f).single { it.rank == 3 }
        assertEquals(0, Color.alpha(pile.bitmap.onPile(behind, 500, 0.5f, 0.2f)))
    }
}
