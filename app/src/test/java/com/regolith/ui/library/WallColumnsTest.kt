package com.regolith.ui.library

import androidx.compose.ui.unit.dp
import com.regolith.domain.display.PostersPerRow
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * How many posters across a Library wall: the owner's Settings › Display ›
 * Posters per row on the inner display, three on a phone, and posters that
 * keep their size when a title's page takes half the window. 832dp is the
 * wall's width on the Fold 8's inner display, beside the rail.
 */
class WallColumnsTest {

    private val fold = 832.dp

    @Test
    fun `a phone shows three whatever the setting`() {
        for (perRow in 5..7) assertEquals(3, wallColumns(375.dp, wide = false, perRow = perRow, fullWidth = 375.dp))
    }

    @Test
    fun `the inner display shows as many across as asked for`() {
        for (perRow in 5..7) assertEquals(perRow, wallColumns(fold, wide = true, perRow = perRow, fullWidth = fold))
        // Held sideways the wall is wider, and the count is still the one asked for.
        assertEquals(5, wallColumns(1100.dp, wide = true, perRow = 5, fullWidth = 1100.dp))
    }

    @Test
    fun `a window too narrow for that many shows as many as fit at the smallest poster`() {
        // The 739dp AVD's wall: seven would make 78dp posters, below the 104dp floor.
        assertEquals(5, wallColumns(601.dp, wide = true, perRow = 7, fullWidth = 601.dp))
        assertEquals(5, wallColumns(601.dp, wide = true, perRow = 5, fullWidth = 601.dp))
    }

    @Test
    fun `with a title's page open the posters keep their size and the columns go`() {
        val besidePage = 347.dp
        assertEquals("seven across become three", 3, wallColumns(besidePage, wide = true, perRow = 7, fullWidth = fold))
        assertEquals("big posters stay big: two", 2, wallColumns(besidePage, wide = true, perRow = 5, fullWidth = fold))
    }

    @Test
    fun `never fewer than two`() {
        assertEquals(2, wallColumns(150.dp, wide = true, perRow = 7, fullWidth = fold))
    }

    @Test
    fun `the setting reads back what was stored, and seven otherwise`() {
        assertEquals(PostersPerRow.SIX, PostersPerRow.of("SIX"))
        assertEquals(PostersPerRow.SEVEN, PostersPerRow.of(null))
        assertEquals(PostersPerRow.SEVEN, PostersPerRow.of("FOUR"))
        assertEquals(listOf("5", "6", "7"), PostersPerRow.entries.map { it.label })
    }
}
