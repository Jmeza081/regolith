package com.regolith.ui.adaptive

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [WindowShape.largeLandscape]: the one layout question [WindowShape.wide]
 * cannot answer, because a phone turned sideways is wide too.
 */
class WindowShapeTest {
    private fun window(width: Int, height: Int) =
        WindowShape(wide = width >= 600, posture = FoldPosture.FLAT, hinge = null, width = width.dp, height = height.dp)

    @Test
    fun `the inner display held sideways is`() {
        assertTrue(window(979, 739).largeLandscape)
    }

    @Test
    fun `the inner display held upright is not`() {
        assertFalse(window(739, 979).largeLandscape)
    }

    @Test
    fun `a phone turned sideways is wide but not large`() {
        val phone = window(915, 411)
        assertTrue("wide, by width alone", phone.wide)
        assertFalse(phone.largeLandscape)
    }

    @Test
    fun `a tablet in landscape is`() {
        assertTrue(window(1280, 800).largeLandscape)
    }

    @Test
    fun `the line is at 600dp tall, inclusive`() {
        assertTrue(window(1000, 600).largeLandscape)
        assertFalse(window(1000, 599).largeLandscape)
    }

    @Test
    fun `a square window is not sideways`() {
        assertFalse(window(800, 800).largeLandscape)
    }
}
