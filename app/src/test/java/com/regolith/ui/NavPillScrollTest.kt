package com.regolith.ui

import com.regolith.ui.navigation.NavPillScroll
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When the nav pill gets out of the way of what you are reading.
 *
 * Compose's sign convention throughout: dragging the finger UP to read
 * further DOWN a page is a negative delta. The rule is plain arithmetic on
 * purpose — the composition, the nested-scroll plumbing and the six
 * scrolling containers it sits above are all irrelevant to whether the
 * direction logic is right.
 */
class NavPillScrollTest {

    /** 24dp at a 2x density, the same number the real screen supplies. */
    private fun scroll() = NavPillScroll(thresholdPx = 48f)

    @Test
    fun `reading down the page puts the pill away`() {
        val s = scroll()
        assertFalse("it starts visible", s.hidden)
        s.onScroll(-60f)
        assertTrue(s.hidden)
    }

    @Test
    fun `it stays away for as long as you keep going`() {
        val s = scroll()
        repeat(10) { s.onScroll(-60f) }
        assertTrue("continuing down must not flip it back on", s.hidden)
    }

    @Test
    fun `scrolling back up brings it straight back`() {
        val s = scroll()
        s.onScroll(-60f)
        s.onScroll(60f)
        assertFalse(s.hidden)
    }

    @Test
    fun `a twitch is not a direction`() {
        // A finger is never still. Anything under the threshold has to be
        // ignored, or the pill flickers through a slow drag.
        val s = scroll()
        repeat(5) {
            s.onScroll(-8f)
            s.onScroll(8f)
        }
        assertFalse("jitter must not move it", s.hidden)
    }

    @Test
    fun `reversing spends no distance banked the other way`() {
        val s = scroll()
        // 40px down — not enough on its own.
        s.onScroll(-40f)
        assertFalse(s.hidden)
        // Now 40px back up. If the tally were cumulative this would read as
        // 0 and nothing would happen; the reversal has to start again, so
        // this is 40 of the 48 needed to reveal and it stays visible.
        s.onScroll(40f)
        assertFalse(s.hidden)
        // And the next 40 down is a fresh tally too, not a resumption.
        s.onScroll(-40f)
        assertFalse("the earlier 40 down must not still be banked", s.hidden)
        s.onScroll(-10f)
        assertTrue(s.hidden)
    }

    @Test
    fun `a tap brings the nav back`() {
        val s = scroll()
        s.onScroll(-60f)
        assertTrue(s.hidden)
        s.onPress()
        s.onRelease()
        assertFalse("a press that never scrolled is a tap", s.hidden)
    }

    @Test
    fun `letting go of a drag is not a tap`() {
        // The release at the end of a flick must not flash the pill on over
        // content that is still moving.
        val s = scroll()
        s.onPress()
        s.onScroll(-60f)
        s.onRelease()
        assertTrue(s.hidden)
    }

    @Test
    fun `a new screen always starts with the nav showing`() {
        val s = scroll()
        s.onScroll(-60f)
        s.reveal()
        assertFalse(s.hidden)
    }
}
