package com.regolith.domain

import com.regolith.domain.media.ShortsRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Shorts feed's whole admission rule, against sizes real files actually have. */
class ShortsRuleTest {

    private fun short(w: Int?, h: Int?, rot: Int?, ms: Long?) = ShortsRule.isShort(ms, w, h, rot)

    @Test fun `upright portrait clips are shorts`() {
        assertTrue(short(1080, 1920, null, 15_000))   // 9:16
        assertTrue(short(720, 1280, 0, 45_000))       // 9:16, smaller
        assertTrue(short(1080, 1440, 0, 20_000))      // 3:4 is still portrait
        assertTrue(short(1080, 1620, null, 1))        // 2:3
    }

    /** The case the rotation column exists for: stored landscape, shown upright. */
    @Test fun `a quarter turn is applied before the comparison`() {
        assertTrue(short(1920, 1080, 90, 10_000))
        assertTrue(short(1920, 1080, 270, 10_000))
        assertTrue(short(1920, 1080, -90, 10_000))
        // 180 is upside down, not on its side: still landscape.
        assertFalse(short(1920, 1080, 180, 10_000))
    }

    @Test fun `landscape and square are not shorts`() {
        assertFalse(short(1920, 1080, null, 30_000))
        assertFalse(short(1280, 720, 0, 20_000))
        assertFalse(short(1080, 1080, null, 12_000)) // exactly 1.0 falls outside
        assertFalse(short(1080, 1920, 90, 15_000))   // portrait turned onto its side
    }

    @Test fun `over a minute is not a short, however tall`() {
        assertTrue(short(1080, 1920, null, 60_000))   // the boundary is inclusive
        assertFalse(short(1080, 1920, null, 60_001))
        assertFalse(short(1080, 1920, null, 90_000))
    }

    @Test fun `unmeasured is never a short, rather than a guess`() {
        assertFalse(short(1080, 1920, null, null))    // duration unknown
        assertFalse(short(null, null, null, 15_000))  // size unknown
        assertFalse(short(1080, null, null, 15_000))  // half known is not known
        assertFalse(short(1080, 1920, null, 0))       // a runtime of zero says nothing
        assertFalse(short(0, 0, null, 15_000))
    }

    @Test fun `displayAspect reports what the screen will show`() {
        assertEquals(0.5625f, ShortsRule.displayAspect(1080, 1920, null)!!, 0.0001f)
        assertEquals(0.5625f, ShortsRule.displayAspect(1920, 1080, 90)!!, 0.0001f)
        assertEquals(1.7778f, ShortsRule.displayAspect(1920, 1080, 0)!!, 0.0001f)
        assertNull(ShortsRule.displayAspect(null, 1920, 90))
    }
}
