package com.regolith.data.discovery

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkAddress
import android.net.NetworkCapabilities
import android.util.Log
import com.regolith.domain.smb.SmbHost
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.Socket
import javax.inject.Inject
import javax.inject.Singleton

/** A host that answered on the SMB port. */
data class DiscoveredHost(val host: SmbHost, val name: String, val label: String)

/** Progress of a sweep: how many addresses have been tried. */
sealed interface DiscoveryEvent {
    data class Subnet(val cidr: String, val total: Int) : DiscoveryEvent
    data class Checked(val done: Int, val total: Int) : DiscoveryEvent
    data class Found(val host: DiscoveredHost) : DiscoveryEvent
    data object Finished : DiscoveryEvent
}

/**
 * Finds SMB servers on the Wi-Fi (design section 03, "Searching"): a TCP
 * sweep of port 445 across the phone's subnet with a short timeout, named
 * by reverse DNS where the router answers. Nothing leaves the network;
 * nothing is stored until the user picks a host.
 *
 * No mDNS: from Android 17 (targetSdk 37) `NsdManager.discoverServices`
 * goes through a system "Choose a device to connect" picker instead of
 * handing the app the list, which would put a second, foreign-looking
 * list on top of the design's own. The sweep sees every host the picker
 * would, and more (servers that do not advertise).
 */
@Singleton
class HostDiscovery @Inject constructor(@ApplicationContext private val context: Context) {

    /** The phone's Wi-Fi IPv4 address and prefix, or null when not on Wi-Fi. */
    fun wifiAddress(): LinkAddress? {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val network = cm.activeNetwork ?: return null
        val caps = cm.getNetworkCapabilities(network) ?: return null
        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && !caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) return null
        return cm.getLinkProperties(network)?.linkAddresses?.firstOrNull { it.address is Inet4Address }
    }

    /** Sweep the subnet until it is done or the collector cancels. */
    fun discover(): Flow<DiscoveryEvent> = callbackFlow {
        val link = wifiAddress()
        val found = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

        if (link == null) {
            trySend(DiscoveryEvent.Subnet("no Wi-Fi", 0))
        } else {
            val base = link.address.address
            val prefix = link.prefixLength.coerceIn(16, 30)
            val hostBits = 32 - prefix
            val total = (1 shl hostBits) - 2
            val network = ((base[0].toInt() and 0xff shl 24) or (base[1].toInt() and 0xff shl 16) or (base[2].toInt() and 0xff shl 8) or (base[3].toInt() and 0xff)) and (-1 shl hostBits)
            val cidr = "${(network ushr 24) and 0xff}.${(network ushr 16) and 0xff}.${(network ushr 8) and 0xff}.${network and 0xff}/$prefix"
            trySend(DiscoveryEvent.Subnet(cidr, total))
            launch(Dispatchers.IO) {
                val slots = Semaphore(48)
                var done = 0
                val jobs = (1..total).map { i ->
                    launch {
                        slots.withPermit {
                            val ip = network + i
                            val address = "${(ip ushr 24) and 0xff}.${(ip ushr 16) and 0xff}.${(ip ushr 8) and 0xff}.${ip and 0xff}"
                            if (address != link.address.hostAddress && probe(address)) {
                                if (found.add(address)) trySend(DiscoveryEvent.Found(DiscoveredHost(SmbHost(address), nameFor(address), "$address · SMB")))
                            }
                            synchronized(this@callbackFlow) { done++ }
                            if (done % 8 == 0 || done == total) trySend(DiscoveryEvent.Checked(done, total))
                        }
                    }
                }
                jobs.forEach { it.join() }
                trySend(DiscoveryEvent.Finished)
            }
        }
        awaitClose { }
    }.flowOn(Dispatchers.IO)

    private suspend fun probe(address: String): Boolean = withTimeoutOrNull(PROBE_TIMEOUT_MS + 50) {
        try {
            Socket().use { s ->
                s.connect(InetSocketAddress(address, 445), PROBE_TIMEOUT_MS.toInt())
                true
            }
        } catch (e: Exception) {
            false
        }
    } ?: false

    /** Reverse DNS when the router provides it; the address otherwise. */
    private fun nameFor(address: String): String = try {
        val name = java.net.InetAddress.getByName(address).canonicalHostName
        if (name == address) address else name.substringBefore('.').uppercase()
    } catch (e: Exception) {
        Log.d(TAG, "no name for $address: ${e.message}")
        address
    }

    private companion object {
        const val TAG = "Regolith/Discovery"
        const val PROBE_TIMEOUT_MS = 300L
    }
}
