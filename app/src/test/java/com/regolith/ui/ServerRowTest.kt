package com.regolith.ui

import com.regolith.ui.settings.ServerRow
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The second line of a Settings server row.
 *
 * It matters because of what renaming took away: the row used to identify a
 * server by its name, which only worked while the name WAS the address.
 * Once the name can be anything, the address has to be said here or two
 * renamed NAS boxes are indistinguishable.
 */
class ServerRowTest {

    private fun row(host: String, status: String = "2.4 TB free") =
        ServerRow(serverId = 1, name = "Living room NAS", status = status, scanning = false, host = host)

    @Test
    fun `the address leads, then how the server is doing`() {
        assertEquals("192.168.4.73 · 2.4 TB free", row("192.168.4.73").meta)
        assertEquals("tower.local:1445 · out of reach", row("tower.local:1445", "out of reach").meta)
    }

    @Test
    fun `a server with no address says only how it is doing`() {
        // The demo library is not on the network; an empty " · " before its
        // status would be a gap where a fact should be.
        assertEquals("3 shares", row("", "3 shares").meta)
    }

    @Test
    fun `the row is addressable by its server id`() {
        assertEquals("settings_server_1", row("192.168.4.73").testTag)
    }
}
