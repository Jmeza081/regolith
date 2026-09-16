package com.regolith.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The loader's cycle, as arithmetic. A Compose animation cannot be tested off
 * a device; its shape can, and the one property that matters for something
 * that runs forever is that the loop has no SEAM -- the frame after the last
 * is the first, or the mark visibly jumps once a cycle.
 */
class StrataLoaderTest {
    private val bands = 0 until STRATA_BAND_COUNT

    @Test
    fun `the cycle joins up`() {
        for (i in bands) {
            assertEquals("band $i offset", loopBandOffsetX(i, 0L), loopBandOffsetX(i, LOOP_CYCLE_MS.toLong()), 0.01f)
            assertEquals("band $i alpha", loopBandAlpha(i, 0L), loopBandAlpha(i, LOOP_CYCLE_MS.toLong()), 0.01f)
            // And again a few laps in, where floorMod has to keep holding.
            assertEquals(loopBandOffsetX(i, 0L), loopBandOffsetX(i, LOOP_CYCLE_MS * 7L), 0.01f)
        }
    }

    @Test
    fun `a band starts out, arrives whole, and leaves the way it came`() {
        val i = 0
        val travel = loopBandTravel(i)
        assertEquals(travel, loopBandOffsetX(i, 0L), 0.01f)
        assertEquals(LOOP_ALPHA_FLOOR, loopBandAlpha(i, 0L), 0.01f)
        // Mid-hold: home and solid.
        val hold = (LOOP_CYCLE_MS * 0.5f).toLong()
        assertEquals(0f, loopBandOffsetX(i, hold), 0.01f)
        assertEquals(1f, loopBandAlpha(i, hold), 0.01f)
        // On the way out it goes back to the side it came from, not the other.
        val leaving = (LOOP_CYCLE_MS * 0.9f).toLong()
        assertTrue(loopBandOffsetX(i, leaving) * travel > 0f)
    }

    @Test
    fun `bands run in order, from alternating sides`() {
        assertTrue("even bands come from the left", loopBandTravel(0) < 0f)
        assertTrue("odd bands come from the right", loopBandTravel(1) > 0f)
        // The top band leads the one below it by the stagger.
        assertEquals(loopPhase(0, 1_000L), loopPhase(1, 1_000L + LOOP_STAGGER_MS), 0.001f)
    }

    @Test
    fun `nothing ever leaves the wedge entirely, or goes invisible`() {
        // Same rule the splash learned: a band clipped fully outside the mark
        // draws nothing, and neither does one at zero alpha.
        assertTrue("travel must stay inside the 100-wide viewport", LOOP_TRAVEL < 100f)
        for (i in bands) {
            for (ms in 0L until LOOP_CYCLE_MS.toLong() step 37L) {
                assertTrue(kotlin.math.abs(loopBandOffsetX(i, ms)) <= LOOP_TRAVEL + 0.01f)
                assertTrue(loopBandAlpha(i, ms) in (LOOP_ALPHA_FLOOR - 0.01f)..1.01f)
            }
        }
    }

    @Test
    fun `the phase wraps for a clock that started before zero`() {
        // The stagger subtracts, so early frames ask about negative time.
        for (i in bands) assertTrue(loopPhase(i, 0L) in 0f..1f)
        assertTrue(loopPhase(4, 10L) in 0f..1f)
    }
}
