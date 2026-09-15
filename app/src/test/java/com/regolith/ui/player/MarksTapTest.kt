package com.regolith.ui.player

import org.junit.Assert.assertEquals
import org.junit.Test

class MarksTapTest {
    @Test
    fun `tapping a handle opens it, even while another mark is open`() {
        // Before, a handle ignored taps while another mark was open, so moving
        // on meant scrolling down to Done first.
        assertEquals(MarksTap.Open, marksTap(hit = 3, durationMs = 60_000))
    }

    @Test
    fun `a tap away from every handle scrubs, once the length is known`() {
        assertEquals(MarksTap.Scrub, marksTap(hit = -1, durationMs = 60_000))
        assertEquals(MarksTap.Nothing, marksTap(hit = -1, durationMs = 0))
    }
}
