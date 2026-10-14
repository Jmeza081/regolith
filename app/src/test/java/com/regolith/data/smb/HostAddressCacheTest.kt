package com.regolith.data.smb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.UnknownHostException

/** [HostAddressCache]: names go out as their last good address, and a missed lookup falls back to it. */
class HostAddressCacheTest {
    private var clock = 0L
    private var answer: (String) -> Array<InetAddress> = { arrayOf(v4("192.168.4.23")) }
    private var lookups = 0
    private val cache = HostAddressCache(lookUp = { lookups++; answer(it) }, now = { clock })

    private fun v4(address: String) = InetAddress.getByAddress(address.split('.').map { it.toInt().toByte() }.toByteArray()) as Inet4Address
    private fun v6() = Inet6Address.getByAddress("mac.local", ByteArray(16).also { it[0] = 0xfe.toByte(); it[1] = 0x80.toByte() }, null) as InetAddress

    @Test fun `a name goes out as its address, looked up once while fresh`() {
        assertEquals("192.168.4.23", cache.addressFor("mac.local"))
        clock += HostAddressCache.FRESH_MS - 1
        assertEquals("192.168.4.23", cache.addressFor("MAC.local"))
        assertEquals(1, lookups)
    }

    @Test fun `a missed lookup falls back to the last good address`() {
        cache.addressFor("mac.local")
        clock += HostAddressCache.FRESH_MS
        answer = { throw UnknownHostException(it) }
        assertEquals("192.168.4.23", cache.addressFor("mac.local"))
        assertEquals(2, lookups)
    }

    @Test fun `a name that never resolved fails as a lookup would`() {
        answer = { throw UnknownHostException(it) }
        assertThrows(UnknownHostException::class.java) { cache.addressFor("mac.local") }
    }

    @Test fun `a stale address is looked up again, and the new one replaces it`() {
        cache.addressFor("mac.local")
        cache.markStale("mac.local")
        answer = { arrayOf(v4("192.168.4.40")) }
        assertEquals("192.168.4.40", cache.addressFor("mac.local"))
        assertEquals(2, lookups)
    }

    @Test fun `IPv4 is preferred, and a name with only IPv6 passes through untouched`() {
        answer = { arrayOf(v6(), v4("192.168.4.23")) }
        assertEquals("192.168.4.23", cache.addressFor("mac.local"))
        answer = { arrayOf(v6()) }
        assertEquals("v6only.local", cache.addressFor("v6only.local"))
    }

    @Test fun `addresses are used as they are, with no lookup`() {
        assertEquals("10.0.0.5", cache.addressFor("10.0.0.5"))
        assertEquals("fe80::1", cache.addressFor("fe80::1"))
        assertEquals(0, lookups)
    }
}
