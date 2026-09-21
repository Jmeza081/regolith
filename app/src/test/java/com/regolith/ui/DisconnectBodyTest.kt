package com.regolith.ui

import com.regolith.ui.settings.disconnectBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What "Disconnect TOWER?" promises. The dialog used to say only that the
 * share was untouched, while quietly stranding every copy on the phone.
 */
class DisconnectBodyTest {
    @Test
    fun `with no copies it says nothing about them`() {
        val body = disconnectBody(0)
        assertFalse(body.contains("On this device"))
        assertTrue(body.startsWith("The media list is removed from this device."))
    }

    @Test
    fun `one copy reads as one copy`() {
        assertTrue(disconnectBody(1).contains("The video you kept stays on this phone, under On this device."))
    }

    @Test
    fun `several copies are counted`() {
        assertTrue(disconnectBody(4).contains("The 4 videos you kept stay on this phone, under On this device."))
    }

    @Test
    fun `it always says the share is untouched`() {
        listOf(0, 1, 9).forEach { n ->
            assertTrue("keeps: $n", disconnectBody(n).endsWith("Nothing on the share is touched, and you can add it back with the same address."))
        }
    }

    @Test
    fun `there is one space between the sentences`() {
        assertEquals(-1, disconnectBody(0).indexOf("  "))
        assertEquals(-1, disconnectBody(3).indexOf("  "))
    }
}
