package com.regolith.data.smb

import java.net.Inet4Address
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A server's name → the IPv4 address it last answered at, so SMB requests
 * go to an address rather than looking the name up every time.
 *
 * **Why.** The owner's Mac is `jesses-mac-mini.local`, an mDNS name: the
 * phone finds it by shouting on the Wi-Fi and waiting for the Mac to answer,
 * and phones miss those answers now and then (multicast is the first thing
 * Wi-Fi power saving drops). jcifs looks the name up for every new request,
 * and Android remembers an answer for about two seconds, so one missed
 * answer failed whatever was asked at that moment. Logged on the Fold
 * (2026-10-14): a chapter file's write failed with
 * `UnknownHostException: jesses-mac-mini.local` while the same video was
 * being read from the Mac over its open connection in the same second, and
 * the background retry wrote it fine 30 s later. The share had never gone.
 *
 * **The rules.**
 * - A name resolved in the last [FRESH_MS] is used as its address, with no
 *   lookup. While the app is open the connection monitor asks every minute
 *   anyway ([refresh]), so a Mac that moved is found within a minute.
 * - Older than that, it is looked up again; when the lookup FAILS, the last
 *   good address is used anyway. A name that stops resolving for a moment
 *   is far more likely than a server that moved in that moment.
 * - [markStale] (the address refused or timed out a connection) keeps the
 *   address as that fallback but forces the next request to look the name
 *   up again: the Mac taking a new address on the Wi-Fi is how a cached
 *   address goes wrong, and a lookup is how it is put right.
 * - IPv4 only. An mDNS name often resolves to an IPv6 link-local address
 *   too (`fe80::…%wlan0`), which needs its interface spelled out and does
 *   not survive being put in a URL; when a name has no IPv4 address it is
 *   passed through untouched, as before.
 * - An address typed as one (`192.168.4.23`) is passed through untouched.
 *
 * Web analogy: a DNS cache with stale-if-error, like a CDN serving the last
 * good copy when the origin is down.
 */
@Singleton
class HostAddressCache(
    private val lookUp: (String) -> Array<InetAddress>,
    private val now: () -> Long,
) {
    /** The app's one: the gateway reads it, the connection monitor refreshes it. */
    @Inject constructor() : this(InetAddress::getAllByName, System::currentTimeMillis)

    private data class Entry(val address: String, val atMs: Long, val stale: Boolean = false)

    private val entries = ConcurrentHashMap<String, Entry>()

    /**
     * What to put in the URL for [host]: its IPv4 address, or [host] itself
     * when it is already an address or has no IPv4 one. Blocks on a lookup
     * when the cached address is not fresh: call it off the main thread.
     *
     * @throws UnknownHostException when the name does not resolve and has
     *   never resolved before, as a plain lookup would.
     */
    fun addressFor(host: String): String {
        if (host.isEmpty() || isLiteral(host)) return host
        val key = host.lowercase()
        val cached = entries[key]
        if (cached != null && !cached.stale && now() - cached.atMs < FRESH_MS) return cached.address
        val found = try {
            lookUp(host).firstOrNull { it is Inet4Address }?.hostAddress
        } catch (e: UnknownHostException) {
            // Stale-if-error: the reason this class exists.
            return cached?.address ?: throw e
        }
        if (found == null) return cached?.address ?: host
        entries[key] = Entry(found, now())
        return found
    }

    /** The address in use refused or timed out a connection: look the name up again next time, keeping it as the fallback. */
    fun markStale(host: String) {
        entries.computeIfPresent(host.lowercase()) { _, e -> e.copy(stale = true) }
    }

    /**
     * Look [host] up now, whatever the cache says, keeping the old address
     * when the lookup fails: the connection monitor's once-a-minute check.
     * Returns the address to use, as [addressFor] does.
     */
    fun refresh(host: String): String {
        if (host.isEmpty() || isLiteral(host)) return host
        markStale(host)
        return addressFor(host)
    }

    /** The cached address for [host], if any: for the log line that says which way a request went. */
    fun cached(host: String): String? = entries[host.lowercase()]?.address

    companion object {
        /** How long an answer stands before the name is looked up again: a minute, the owner's choice. */
        const val FRESH_MS = 60_000L

        private val IPV4 = Regex("""\d{1,3}(\.\d{1,3}){3}""")

        /** An IPv4 or IPv6 address written out, not a name. */
        fun isLiteral(host: String): Boolean = IPV4.matches(host) || host.contains(':')
    }
}
