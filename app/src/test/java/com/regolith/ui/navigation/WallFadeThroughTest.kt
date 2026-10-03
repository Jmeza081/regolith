package com.regolith.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The curve a wall fades through on when a page opens or closes beside it
 * ([wallFadeThroughAlpha]). What matters is that the wall is whole before and
 * after, and gone at exactly the moment it changes width, so the change of
 * layout is never seen.
 */
class WallFadeThroughTest {

    private val steps = (0..100).map { it / 100f }

    @Test
    fun `the wall is whole at the start and the end, and gone when it changes width`() {
        assertEquals(1f, wallFadeThroughAlpha(0f), 0f)
        assertEquals(0f, wallFadeThroughAlpha(WALL_FADE_THROUGH_SWITCH), 1e-6f)
        assertEquals(1f, wallFadeThroughAlpha(1f), 0f)
    }

    @Test
    fun `it only fades out before the switch and only fades back in after it`() {
        val out = steps.filter { it < WALL_FADE_THROUGH_SWITCH }.map(::wallFadeThroughAlpha)
        val back = steps.filter { it >= WALL_FADE_THROUGH_SWITCH }.map(::wallFadeThroughAlpha)
        out.zipWithNext().forEach { (a, b) -> assertTrue("fading out: $a then $b", b <= a) }
        back.zipWithNext().forEach { (a, b) -> assertTrue("fading in: $a then $b", b >= a) }
    }

    @Test
    fun `it is almost gone just before the switch, so nothing jumps across it`() {
        assertTrue(wallFadeThroughAlpha(WALL_FADE_THROUGH_SWITCH - 0.001f) < 0.02f)
    }
}
