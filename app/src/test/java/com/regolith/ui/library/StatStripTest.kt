package com.regolith.ui.library

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

/** A collection page's stat strip: one line on a phone, rows of two where four would cut their labels. */
class StatStripTest {

    @Test
    fun `four cells read as one line on a phone and beside the poster`() {
        assertEquals(4, statsPerRow(375.dp, 4))
        assertEquals(4, statsPerRow(480.dp, 4))
    }

    @Test
    fun `beside a title's page they fold into rows of two`() {
        assertEquals(2, statsPerRow(220.dp, 4))
        assertEquals(2, statsPerRow(220.dp, 3))
    }

    @Test
    fun `two cells or fewer never fold`() {
        assertEquals(2, statsPerRow(120.dp, 2))
        assertEquals(1, statsPerRow(120.dp, 1))
    }
}
