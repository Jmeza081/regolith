package com.regolith.desktop.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class LeaveGuardTest {
    @Test
    fun `with nothing unsaved, leaving happens at once`() {
        val guard = LeaveGuard()
        var left = 0
        guard.request { left++ }
        assertEquals(1, left)
        assertNull(guard.pending)
    }

    @Test
    fun `with unsaved changes, leaving waits for an answer`() {
        val guard = LeaveGuard().apply { unsaved = true }
        var left = 0
        guard.request { left++ }
        assertEquals(0, left)
        assertNotNull(guard.pending)
        guard.pending!!.invoke()
        assertEquals(1, left)
    }
}
