package com.regolith.domain.model

import com.regolith.domain.smb.SmbHost
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What an address row is allowed to claim about itself.
 *
 * Written after a real failure: the owner's Mac took a new DHCP lease, the
 * pinned address stopped existing, and the page went on saying "answered
 * in 73 ms" about it — a true statement about hours ago, presented as the
 * present tense.
 */
class ServerAddressHealthTest {
    private val now = 1_000_000_000L

    private fun address(ok: Long?, tried: Long?, rtt: Int? = 73, host: String = "192.168.4.82") = ServerAddress(
        id = 1, serverId = 1, label = "Direct IP", host = SmbHost(host, 445),
        lastOkAtMs = ok, lastRttMs = rtt, lastTriedAtMs = tried,
    )

    @Test
    fun `tried later than it worked is failing`() {
        assertTrue(address(ok = now - 7_200_000, tried = now).failing)
    }

    @Test
    fun `tried and never once answered is failing`() {
        assertTrue(address(ok = null, tried = now, rtt = null).failing)
    }

    @Test
    fun `answered on the last try is not failing`() {
        assertFalse(address(ok = now, tried = now).failing)
    }

    @Test
    fun `never tried is not failing`() {
        assertFalse("an address nobody has used yet is not broken", address(ok = null, tried = null, rtt = null).failing)
    }

    @Test
    fun `a failing address says so instead of quoting a stale timing`() {
        val detail = address(ok = now - 7_200_000, tried = now).detail(now)
        assertTrue("was: $detail", detail.contains("no answer"))
        assertTrue("says how long it has been", detail.contains("2 hours ago"))
        assertFalse("must not still claim a speed", detail.contains("73 ms"))
    }

    @Test
    fun `a healthy address reports its speed`() {
        assertTrue(address(ok = now, tried = now).detail(now).contains("answered in 73 ms"))
    }

    @Test
    fun `a literal home address is flagged as one that may move`() {
        assertTrue(address(ok = now, tried = now).mayMove)
        assertFalse(address(ok = now, tried = now, host = "jesses-mac-mini.local").mayMove)
    }

    @Test
    fun `how long ago reads the way a person would say it`() {
        assertEquals("just now", relativeWhen(now - 30_000, now))
        assertEquals("11 minutes ago", relativeWhen(now - 660_000, now))
        assertEquals("an hour ago", relativeWhen(now - 4_000_000, now))
        assertEquals("5 hours ago", relativeWhen(now - 18_000_000, now))
        assertEquals("yesterday", relativeWhen(now - 100_000_000, now))
    }
}
