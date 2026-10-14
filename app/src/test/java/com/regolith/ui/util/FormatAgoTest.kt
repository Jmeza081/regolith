package com.regolith.ui.util

import org.junit.Assert.assertEquals
import org.junit.Test

class FormatAgoTest {
    @Test fun `ago counts seconds, then minutes, then hours`() {
        assertEquals("just now", formatAgo(10_000, 11_000))
        assertEquals("4 s ago", formatAgo(10_000, 14_000))
        assertEquals("3 min ago", formatAgo(0, 3 * 60_000 + 5_000))
        assertEquals("2 h ago", formatAgo(0, 2 * 3_600_000L + 1))
    }
}
