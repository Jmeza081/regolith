package com.regolith.data.artwork

import android.graphics.Bitmap
import android.graphics.Color
import androidx.core.graphics.createBitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.regolith.domain.artwork.DeckLayout
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
    /** A 16:9 frame of one colour, the size the deck's frames are grabbed at. */
    private fun frame(color: Int): Bitmap = createBitmap(800, 450).apply { eraseColor(color) }

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
        val front = DeckLayout.cards(500f, 750f, 5).last()
        assertEquals(Color.RED, deck.on(front, 0.5f, 0.5f))
    }

    @Test fun `a card further back shows its own frame, dimmed`() {
        val frames = listOf(Color.RED, Color.RED, Color.BLUE, Color.RED, Color.RED).map(::frame)
        val deck = DeckPainter.paint(frames, 500, 750)
        // The third frame is the left one of the middle pair, out of the
        // front card's way (and out of its shadow) near its own left edge.
        val third = DeckLayout.cards(500f, 750f, 5).single { it.rank == 2 }
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
        val front = DeckLayout.looseCards(deck.width.toFloat(), deck.height.toFloat(), 5).last()
        assertEquals(Color.RED, deck.on(front, 0.5f, 0.5f))
    }

    @Test fun `a thumb deals the same hand at its own shape`() {
        val deck = DeckPainter.paint(listOf(frame(Color.GREEN)), 320, 180)
        assertEquals(320, deck.width)
        val front = DeckLayout.cards(320f, 180f, 1).single()
        assertEquals(Color.GREEN, deck.on(front, 0.5f, 0.5f))
    }
}
