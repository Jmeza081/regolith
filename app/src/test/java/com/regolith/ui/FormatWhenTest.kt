package com.regolith.ui

import com.regolith.ui.util.formatWhen
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar

class FormatWhenTest {
    private val now: Long = Calendar.getInstance().apply { set(2026, Calendar.SEPTEMBER, 10, 15, 0, 0) }.timeInMillis
    private val hour = 3_600_000L

    @Test fun `today, last night, a weekday, then a date`() {
        assertEquals("today", formatWhen(now - 2 * hour, now))
        assertEquals("last night", formatWhen(now - 20 * hour, now))
        assertEquals("Tuesday", formatWhen(now - 48 * hour, now))
        assertEquals("1 Sep", formatWhen(now - 9 * 24 * hour, now))
    }
}
