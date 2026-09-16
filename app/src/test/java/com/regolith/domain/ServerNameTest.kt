package com.regolith.domain

import com.regolith.domain.model.MAX_SERVER_NAME
import com.regolith.domain.model.defaultServerName
import com.regolith.domain.model.serverNameOrDefault
import com.regolith.domain.smb.SmbHost
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What a server is called. The rule matters twice: once when a server is
 * added and nobody has named it, and again every time the user clears the
 * name field, which has to land back on the default rather than on nothing.
 */
class ServerNameTest {

    @Test
    fun `a hostname becomes its first label, uppercased`() {
        assertEquals("TOWER", defaultServerName(SmbHost("tower.local")))
        assertEquals("TOWER", defaultServerName(SmbHost("Tower")))
        assertEquals("NAS-01", defaultServerName(SmbHost("nas-01.lan")))
    }

    @Test
    fun `an address has no friendlier form, so it stays itself`() {
        assertEquals("192.168.4.73", defaultServerName(SmbHost("192.168.4.73")))
        assertEquals("fe80::1", defaultServerName(SmbHost("fe80::1")))
    }

    @Test
    fun `a typed name is kept, trimmed`() {
        assertEquals("Living room NAS", serverNameOrDefault("  Living room NAS  ", SmbHost("192.168.4.73")))
    }

    @Test
    fun `clearing the name goes back to the default, never to blank`() {
        assertEquals("192.168.4.73", serverNameOrDefault("", SmbHost("192.168.4.73")))
        assertEquals("TOWER", serverNameOrDefault("   ", SmbHost("tower.local")))
    }

    @Test
    fun `a name longer than a row can show is cut at the limit`() {
        val long = "x".repeat(MAX_SERVER_NAME + 10)
        assertEquals(MAX_SERVER_NAME, serverNameOrDefault(long, SmbHost("tower")).length)
    }
}
