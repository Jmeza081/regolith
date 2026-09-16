package com.regolith.ui

import com.regolith.ui.util.msUntilNextMinute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The player's wall clock waits for the minute to turn rather than ticking
 * every 60 s from whenever it started, or it shows the wrong minute for up
 * to a minute at a time.
 */
class ClockTickTest {
    @Test
    fun `it waits for the boundary, not a fixed minute`() {
        assertEquals(60_000L, msUntilNextMinute(0L))
        assertEquals(59_000L, msUntilNextMinute(1_000L))
        assertEquals(1L, msUntilNextMinute(59_999L))
        assertEquals(60_000L, msUntilNextMinute(60_000L))
        assertEquals(30_000L, msUntilNextMinute(90_000L))
    }

    @Test
    fun `the wait is always a real, bounded delay`() {
        // Zero would spin; over a minute would skip one.
        for (ms in 0L..121_000L step 997L) {
            val wait = msUntilNextMinute(ms)
            assertTrue("wait $wait at $ms", wait in 1L..60_000L)
        }
    }

    @Test
    fun `a clock before the epoch still counts forward`() {
        // Kotlin's % returns a negative for a negative left side, which would
        // hand the composable a negative delay. floorMod is why it does not.
        assertTrue(msUntilNextMinute(-1_000L) in 1L..60_000L)
        assertEquals(1_000L, msUntilNextMinute(-1_000L))
    }
}
