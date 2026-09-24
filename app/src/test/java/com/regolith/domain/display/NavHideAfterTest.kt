package com.regolith.domain.display

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NavHideAfterTest {
    @Test fun `a stored name reads back as itself`() {
        NavHideAfter.entries.forEach { assertEquals(it, NavHideAfter.of(it.name)) }
    }

    @Test fun `nothing stored, or something unknown, is the default`() {
        assertEquals(NavHideAfter.DEFAULT, NavHideAfter.of(null))
        assertEquals(NavHideAfter.DEFAULT, NavHideAfter.of("THREE_SECONDS"))
    }

    @Test fun `the choices run shortest first and none is the old three seconds`() {
        val ms = NavHideAfter.entries.map { it.idleMs }
        assertEquals(ms.sorted(), ms)
        assertTrue(ms.all { it > 3_000L })
    }
}
