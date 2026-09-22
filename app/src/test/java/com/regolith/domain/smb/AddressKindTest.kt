package com.regolith.domain.smb

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which addresses are worth a word of warning.
 *
 * The case this exists for is real: a Mac mini took a new DHCP lease, the
 * literal address the app was pinned to stopped existing, and a library
 * that was working read as out of reach.
 */
class AddressKindTest {
    @Test
    fun `a home router's address is the one that moves`() {
        assertTrue(isLikelyToMove("192.168.4.82"))
        assertTrue(isLikelyToMove("10.0.0.14"))
        assertTrue(isLikelyToMove("172.16.5.9"))
    }

    @Test
    fun `a name follows the machine`() {
        assertFalse("mDNS is the fix the owner reached for", isLikelyToMove("jesses-mac-mini.local"))
        assertFalse(isLikelyToMove("jesses-mac-mini.tailc0eb16.ts.net"))
        assertFalse(isLikelyToMove("tower"))
    }

    @Test
    fun `a tailscale address is not a home router's`() {
        assertFalse("100.64 upwards is carrier-grade space, and Tailscale's is stable", isLikelyToMove("100.81.77.114"))
    }

    @Test
    fun `172 outside the private block is somebody else's`() {
        assertFalse(isLikelyToMove("172.15.0.1"))
        assertFalse(isLikelyToMove("172.32.0.1"))
    }

    @Test
    fun `something that only looks like an address is not one`() {
        assertFalse(isLikelyToMove("192.168.4"))
        assertFalse(isLikelyToMove("192.168.4.999"))
        assertFalse(isLikelyToMove("192.168.4.x"))
    }
}
