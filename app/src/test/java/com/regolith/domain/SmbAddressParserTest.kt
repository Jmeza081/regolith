package com.regolith.domain

import com.regolith.domain.smb.SmbAddressParser
import com.regolith.domain.smb.SmbHost
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SmbAddressParserTest {
    @Test fun `bare ip`() =
        assertEquals(SmbHost("192.168.1.24"), SmbAddressParser.parse("192.168.1.24")!!.host)

    @Test fun `smb scheme with share and trailing slash`() {
        val p = SmbAddressParser.parse("smb://Tower/media/")!!
        assertEquals(SmbHost("tower"), p.host)
        assertEquals("media", p.share)
    }

    @Test fun `windows unc form`() {
        val p = SmbAddressParser.parse("\\\\tower\\backups")!!
        assertEquals("tower", p.host.host)
        assertEquals("backups", p.share)
    }

    @Test fun `explicit port`() =
        assertEquals(SmbHost("nas.local", 1445), SmbAddressParser.parse("smb://nas.local:1445")!!.host)

    @Test fun `garbage is null`() {
        assertNull(SmbAddressParser.parse("   "))
        assertNull(SmbAddressParser.parse("smb://"))
        assertNull(SmbAddressParser.parse("tower:notaport"))
    }
}
