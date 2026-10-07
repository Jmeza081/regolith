package com.regolith.ui.player

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Where the mini player's picture sits, which is where the player's picture
 * shrinks to when it is put away: [miniBarPictureRect] for a phone's bar and
 * [miniCardPictureRect] for a wide window's card. Worked in dp at 1x so the
 * numbers read as the layout's own: a 57dp bar, its picture 6dp in, 16:9.
 */
class MiniPlayerGeometryTest {

    private val density = Density(1f)
    private val phone = Size(411f, 915f)

    @Test
    fun `a phone's bar holds its picture at its start, 16 by 9 in 45dp`() {
        val rect = miniBarPictureRect(phone, bottomInset = 24.dp, lift = 0.dp, density = density)
        // s18 gutter + 6dp inset.
        assertEquals(24f, rect.left, 0.01f)
        assertEquals(45f, rect.height, 0.01f)
        assertEquals(80f, rect.width, 0.01f)
        // The bar's foot sits s8 above the navigation bar: 915 - 24 - 8 - 57 + 6.
        assertEquals(832f, rect.top, 0.01f)
    }

    @Test
    fun `above the pill the picture rides as high as the pill and its gap`() {
        val low = miniBarPictureRect(phone, bottomInset = 24.dp, lift = 0.dp, density = density)
        val high = miniBarPictureRect(phone, bottomInset = 24.dp, lift = 70.dp, density = density)
        assertEquals(70f, low.top - high.top, 0.01f)
    }

    @Test
    fun `a wide window's card holds its picture across its top, in the corner`() {
        val window = Size(739f, 979f)
        val rect = miniCardPictureRect(window, bottomInset = 0.dp, end = 18.dp, density = density)
        assertEquals(739f - 18f, rect.right, 0.01f)
        assertEquals(300f, rect.width, 0.01f)
        assertEquals(168.75f, rect.height, 0.01f)
        // The card's top: s18 above the bottom, the card 168.75 + 56 tall.
        assertEquals(979f - 18f - 224.75f, rect.top, 0.01f)
    }
}
