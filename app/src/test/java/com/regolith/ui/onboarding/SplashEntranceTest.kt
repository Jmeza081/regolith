package com.regolith.ui.onboarding

import com.regolith.ui.components.STRATA_BAND_COUNT
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The splash animation itself needs a device; its timing does not. These
 * pin the two things that would be silently wrong on a phone: an entrance
 * that outlasts the splash, and a band that never arrives.
 */
class SplashEntranceTest {
    @Test
    fun `the entrance finishes before the splash fades`() {
        assertTrue("entrance $ENTRANCE_TOTAL_MS ms must fit inside $SPLASH_MS ms", ENTRANCE_TOTAL_MS < SPLASH_MS)
        // The last band is the slowest thing in the mark itself.
        val lastBandEndsMs = (STRATA_BAND_COUNT - 1) * BAND_STAGGER_MS + BAND_DURATION_MS
        assertTrue(lastBandEndsMs <= WORDMARK_DELAY_MS + WORDMARK_DURATION_MS)
    }

    @Test
    fun `every band starts outside the mark and ends home, opaque`() {
        for (i in 0 until STRATA_BAND_COUNT) {
            assertEquals(bandTravel(i), bandOffsetX(i, 0L), 0.01f)
            assertEquals(BAND_ALPHA_FLOOR, bandAlpha(i, 0L), 0.01f)
            assertEquals(0f, bandOffsetX(i, ENTRANCE_TOTAL_MS.toLong()), 0.01f)
            assertEquals(1f, bandAlpha(i, ENTRANCE_TOTAL_MS.toLong()), 0.01f)
        }
    }

    @Test
    fun `the mark is never empty, not even on the first frame`() {
        // A band is clipped to the wedge, which is 100 units wide, so a band
        // that starts a full width out draws nothing at all: the screen goes
        // blank between the system splash and the entrance. Caught on the
        // emulator, pinned here.
        // Two ways to be invisible, and the first version of this test only
        // caught one: a band clipped outside the wedge draws nothing, and so
        // does a band at zero alpha. The screen was measurably blank for
        // 400 ms while this test passed on position alone.
        assertTrue("a band must start inside the wedge, not beyond it", BAND_TRAVEL < 100f)
        assertTrue("a band must be visible before it moves", BAND_ALPHA_FLOOR > 0.2f)
        for (i in 0 until STRATA_BAND_COUNT) {
            val visibleAtRest = 100f - kotlin.math.abs(bandOffsetX(i, 0L))
            assertTrue("band $i is clipped out of the mark at t=0", visibleAtRest > 20f)
            assertTrue("band $i is transparent at t=0", bandAlpha(i, 0L) > 0.2f)
        }
    }

    @Test
    fun `bands arrive in order, from alternating sides`() {
        // Mid-flight the top band is further along than the bottom one.
        val mid = 300L
        assertTrue(bandAlpha(0, mid) > bandAlpha(STRATA_BAND_COUNT - 1, mid))
        assertTrue("even bands come from the left", bandTravel(0) < 0f)
        assertTrue("odd bands come from the right", bandTravel(1) > 0f)
    }

    @Test
    fun `the wordmark waits, then rises once`() {
        assertEquals(0f, wordmarkProgress(0L), 0.001f)
        assertEquals(0f, wordmarkProgress(WORDMARK_DELAY_MS.toLong()), 0.001f)
        assertTrue(wordmarkProgress((WORDMARK_DELAY_MS + 200).toLong()) > 0f)
        assertEquals(1f, wordmarkProgress(ENTRANCE_TOTAL_MS.toLong()), 0.001f)
        assertEquals(1f, wordmarkProgress(9_000L), 0.001f)
    }

    @Test
    fun `progress never leaves zero to one`() {
        for (t in -500L..2_000L step 25L) {
            for (i in 0 until STRATA_BAND_COUNT) {
                assertTrue(bandAlpha(i, t) in 0f..1f)
            }
            assertTrue(wordmarkProgress(t) in 0f..1f)
        }
    }
}
