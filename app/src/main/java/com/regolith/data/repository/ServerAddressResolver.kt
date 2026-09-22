package com.regolith.data.repository

import android.util.Log
import com.regolith.data.db.ServerAddressDao
import com.regolith.data.db.ServerAddressEntity
import com.regolith.data.db.ServerDao
import com.regolith.domain.media.LocalSource
import com.regolith.domain.smb.AddressProbe
import com.regolith.domain.smb.SmbHost
import com.regolith.domain.smb.chooseAddress
import com.regolith.domain.smb.isSlowLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * "Which way in should I use for this server, right now?"
 *
 * A server can be reached more than one way ([ServerAddressEntity]) — a
 * LAN address at home, a VPN name from anywhere — and which is best is a
 * fact about where the PHONE is, not about the server. So it is measured
 * rather than configured: every address is probed at once and the fastest
 * answer wins ([chooseAddress]).
 *
 * **Why a bare TCP connect and not an SMB negotiate.** The question is
 * "is this route alive, and how far away", which a connect answers in one
 * round trip with no credentials, no dialect ladder and nothing to clean
 * up. `HostDiscovery` already sweeps the LAN the same way.
 *
 * **Why it writes `servers.host`.** Every SMB caller in the app — the
 * scan, Browse, the player's resolver, the download queue, chapter sync —
 * reads `host`/`port` off the server row. Keeping that column pointed at
 * whatever currently answers means all of them get the right route without
 * knowing this class exists.
 *
 * Web analogy: happy-eyeballs (RFC 8305) over a host's A records, with the
 * winner cached.
 */
@Singleton
class ServerAddressResolver @Inject constructor(
    private val serverDao: ServerDao,
    private val addressDao: ServerAddressDao,
) {
    private data class Chosen(val host: SmbHost, val atMs: Long)

    /** The winner per server, so a walk of a thousand folders races once, not a thousand times. */
    private val chosen = ConcurrentHashMap<Long, Chosen>()

    /**
     * The address to use for [serverId].
     *
     * Falls back to whatever is on the server row whenever there is nothing
     * better to say — no addresses, a pinned choice, a source that has no
     * network behind it at all — so this can never be the reason a server
     * stops working.
     */
    suspend fun hostFor(serverId: Long): SmbHost {
        val server = serverDao.byId(serverId) ?: return SmbHost("", PORT)
        val current = SmbHost(server.host, server.port)
        // The demo library and "On this device" have no host to race.
        if (LocalSource.isLocal(server.host)) return current

        val addresses = addressDao.forServer(serverId)
        if (addresses.isEmpty()) return current
        if (addresses.size == 1) {
            val only = addresses.single()
            return SmbHost(only.host, only.port).also { if (it != current) serverDao.setAddress(serverId, only.host, only.port) }
        }

        chosen[serverId]?.let { if (System.currentTimeMillis() - it.atMs < CACHE_MS) return it.host }

        // A pin is a PREFERENCE, not a vow. Try it first and use it when it
        // answers; when it does not, fall back to whatever does rather than
        // reporting a server that is plainly reachable as out of reach. The
        // owner's Mac taking a new DHCP lease is what taught this: the old
        // address went quiet, the pin held, and a working library read as
        // offline until someone went and looked.
        val pinned = server.pinnedAddressId?.let { id -> addresses.firstOrNull { it.id == id } }
        if (server.addressMode == PINNED && pinned != null) {
            val rtt = probe(pinned.host, pinned.port)
            val now = System.currentTimeMillis()
            if (rtt != null) {
                addressDao.markAnswered(pinned.id, now, rtt)
                val host = SmbHost(pinned.host, pinned.port)
                chosen[serverId] = Chosen(host, now)
                if (server.host != pinned.host || server.port != pinned.port) serverDao.setAddress(serverId, pinned.host, pinned.port)
                return host
            }
            addressDao.markTried(pinned.id, now)
            Log.w(TAG, "server $serverId: pinned ${pinned.host} did not answer; trying the others")
        }
        return race(serverId, addresses) ?: current
    }

    /**
     * Whether the route in use for [serverId] is slow enough that
     * unattended work should wait for a better one.
     *
     * Read from the timings the last race wrote, so it costs a query and
     * never a probe — the caller is usually a worker about to decide
     * whether to start hours of work, not a screen.
     */
    suspend fun isSlowLinkFor(serverId: Long): Boolean {
        val server = serverDao.byId(serverId) ?: return false
        val rows = addressDao.forServer(serverId)
        if (rows.size <= 1) return false
        val probes = rows.map { AddressProbe(it.id, it.host, it.port, it.lastRttMs) }
        val chosen = probes.firstOrNull { it.host == server.host && it.port == server.port }
        return isSlowLink(chosen, probes.filter { it.id != chosen?.id })
    }

    /**
     * Race now, rather than at whatever the next SMB call turns out to be.
     *
     * Choosing "Automatic", or adding an address, is a decision that should
     * land when it is made: without this the page went on naming the route
     * it had been pinned to, which is true but stale, and reads exactly like
     * the setting not having worked.
     */
    suspend fun refresh(serverId: Long) {
        forget(serverId)
        hostFor(serverId)
    }

    /** Forget the cached winner: the network changed, or the user edited the addresses. */
    fun forget(serverId: Long? = null) {
        if (serverId == null) chosen.clear() else chosen.remove(serverId)
    }

    /**
     * Probe every address at once and keep the fastest that answered.
     *
     * All of them are tried even after one succeeds: the point is to learn
     * which is FASTEST, and the losers' timings are what let the UI say how
     * much slower the route in use is. One extra connect per address, once
     * a minute at most, against a scan that makes thousands.
     */
    private suspend fun race(serverId: Long, addresses: List<ServerAddressEntity>): SmbHost? = coroutineScope {
        val probes = addresses.map { address ->
            async(Dispatchers.IO) {
                val rtt = probe(address.host, address.port)
                val now = System.currentTimeMillis()
                if (rtt != null) addressDao.markAnswered(address.id, now, rtt) else addressDao.markTried(address.id, now)
                AddressProbe(address.id, address.host, address.port, rtt)
            }
        }.awaitAll()

        val winner = chooseAddress(probes) ?: run {
            Log.w(TAG, "server $serverId: none of ${probes.size} addresses answered")
            return@coroutineScope null
        }
        val host = SmbHost(winner.host, winner.port)
        chosen[serverId] = Chosen(host, System.currentTimeMillis())
        // The one write the rest of the app reads. Only when it changed, so a
        // stable network is not writing a row every minute.
        val server = serverDao.byId(serverId)
        if (server != null && (server.host != winner.host || server.port != winner.port)) {
            Log.i(TAG, "server $serverId: using ${winner.host}:${winner.port} (${winner.rttMs}ms) over ${server.host}:${server.port}")
            serverDao.setAddress(serverId, winner.host, winner.port)
        }
        host
    }

    /** Milliseconds to open a TCP connection, or null when it would not open. */
    /**
     * Milliseconds to open a TCP connection, or null when it would not open.
     *
     * **Name resolution is resolved first and NOT counted**, which is the
     * whole subtlety. `InetSocketAddress(host, port)` resolves on the spot,
     * so timing the connect from before that point charges the address for
     * its DNS — and an mDNS name like `mac-mini.local` takes the better part
     * of a second to resolve by multicast. Measured on the owner's phone:
     * the `.local` address timed at 1160 ms against 78 ms for the Tailscale
     * name, which would have made "use whichever is fastest" pick the VPN
     * over the LAN — precisely backwards, and for a cost paid once per
     * process rather than per read.
     *
     * What the chooser needs is the LINK's latency, so that is what this
     * returns: resolve, then time the connect to the address that came back.
     */
    private suspend fun probe(host: String, port: Int): Int? = withContext(Dispatchers.IO) {
        try {
            val resolved = InetAddress.getByName(host)
            val started = System.nanoTime()
            Socket().use { it.connect(InetSocketAddress(resolved, port), PROBE_TIMEOUT_MS) }
            ((System.nanoTime() - started) / 1_000_000).toInt().coerceAtLeast(1)
        } catch (e: Exception) {
            null
        }
    }

    private companion object {
        const val TAG = "Regolith/Address"
        const val PINNED = "PINNED"
        const val PORT = 445

        /**
         * Short enough that a route which is merely far away still answers,
         * and an address that is genuinely absent is given up on quickly —
         * this runs before anything else can happen, so it is felt.
         */
        const val PROBE_TIMEOUT_MS = 2_000

        /** How long a winner stands before the addresses are raced again. */
        const val CACHE_MS = 60_000L
    }
}
