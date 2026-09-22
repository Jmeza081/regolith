package com.regolith.domain.smb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

/** The rule that decides which way in to use. */
class AddressChoiceTest {
    private fun probe(id: Long, rtt: Int?) = AddressProbe(id, "h$id", 445, rtt)

    @Test
    fun `the fastest answer wins`() {
        val chosen = chooseAddress(listOf(probe(1, 34), probe(2, 2)))
        assertEquals("the LAN address, because it is faster, not because it is a LAN", 2L, chosen!!.id)
    }

    @Test
    fun `an address that did not answer cannot win, however many there are`() {
        assertEquals(3L, chooseAddress(listOf(probe(1, null), probe(2, null), probe(3, 180)))!!.id)
    }

    @Test
    fun `nothing answering is the only out of reach`() {
        assertNull(chooseAddress(listOf(probe(1, null), probe(2, null))))
        assertNull(chooseAddress(emptyList()))
    }

    @Test
    fun `a tie keeps the earlier address, so a stable network does not flap`() {
        assertEquals(1L, chooseAddress(listOf(probe(1, 9), probe(2, 9)))!!.id)
    }

    @Test
    fun `slowdown says how much worse the route in use is`() {
        val ts = probe(2, 180)
        assertEquals(90.0, slowdownFactor(ts, listOf(probe(1, 2)))!!, 0.001)
    }

    @Test
    fun `the fastest route is not slower than itself`() {
        assertEquals(1.0, slowdownFactor(probe(1, 2), listOf(probe(2, 180)))!!, 0.001)
    }

    @Test
    fun `nothing to compare gives no factor`() {
        assertNull(slowdownFactor(null, listOf(probe(1, 2))))
        assertNull(slowdownFactor(probe(1, null), listOf(probe(2, null))))
    }

    @Test
    fun `the owner's own two addresses are a slow link`() {
        assertTrue("30 ms on the LAN against 92 over the tunnel", isSlowLink(probe(2, 92), listOf(probe(1, 30))))
    }

    @Test
    fun `the fast way in is never the slow one`() {
        assertFalse(isSlowLink(probe(1, 30), listOf(probe(2, 92))))
    }

    @Test
    fun `one address alone is never slow, however far away it is`() {
        assertFalse(
            "a NAS at the end of a slow line is not far away, it is just what this setup costs",
            isSlowLink(probe(1, 400), emptyList()),
        )
    }
}
