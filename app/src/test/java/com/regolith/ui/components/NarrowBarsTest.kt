package com.regolith.ui.components

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * How the top bar and the segmented tabs give way in a narrow column: the
 * wall beside a title's page on the inner display, about 256dp across.
 * A phone (411dp) and the inner display's full width must not change.
 */
class NarrowBarsTest {

    @Test
    fun `a phone-wide bar keeps all three actions beside a back arrow`() {
        assertEquals(3, topBarActionsShown(411.dp, hasBack = true, actions = 3))
        assertEquals(3, topBarActionsShown(360.dp, hasBack = true, actions = 3))
        assertEquals(3, topBarActionsShown(739.dp, hasBack = true, actions = 3))
    }

    @Test
    fun `beside a title's page the actions fold into More and the title gets the room`() {
        // 256 - 18 - 8 - (32 + 12) = 186 before any icon: three would leave 54,
        // two plus More 54, one plus More 98, More alone 142.
        assertEquals(0, topBarActionsShown(256.dp, hasBack = true, actions = 3))
        // Somewhat wider, one action can stay beside More.
        assertEquals(1, topBarActionsShown(310.dp, hasBack = true, actions = 3))
    }

    @Test
    fun `a single action never folds, as More would only take its place`() {
        assertEquals(1, topBarActionsShown(200.dp, hasBack = true, actions = 1))
        assertEquals(0, topBarActionsShown(200.dp, hasBack = true, actions = 0))
    }

    @Test
    fun `segments share the width equally while every one fits`() {
        assertEquals(listOf(100, 100, 100), segmentWidths(300, full = listOf(80, 95, 70), bare = listOf(50, 60, 45)))
    }

    @Test
    fun `when an equal share is too narrow, each takes what it needs and shares the rest`() {
        // 130 does not fit an equal 100, but all three with their counts fit 300.
        assertEquals(listOf(70, 130, 100), segmentWidths(300, full = listOf(60, 120, 90), bare = listOf(40, 70, 60)))
    }

    @Test
    fun `tighter still, the counts give way and the labels stay whole`() {
        // With counts they need 270, without 180: each gets its label plus 10.
        assertEquals(listOf(70, 80, 60), segmentWidths(210, full = listOf(90, 100, 80), bare = listOf(60, 70, 50)))
    }

    @Test
    fun `too narrow even for the labels, they shrink in proportion and still fill the row`() {
        val widths = segmentWidths(100, full = listOf(90, 100, 80), bare = listOf(60, 70, 40))
        assertEquals(100, widths.sum())
        assertEquals(listOf(35, 41, 24), widths)
    }

    @Test
    fun `rounding never leaves the row short`() {
        assertEquals(301, segmentWidths(301, full = listOf(10, 10, 10), bare = listOf(5, 5, 5)).sum())
        assertEquals(emptyList<Int>(), segmentWidths(301, full = emptyList(), bare = emptyList()))
    }
}
