package com.regolith.data.connection

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import com.regolith.data.db.ChapterSyncDao
import com.regolith.data.db.ServerDao
import com.regolith.data.media.ChapterSyncRepository
import com.regolith.data.repository.ServerAddressResolver
import com.regolith.data.repository.SourceRepository
import com.regolith.data.smb.HostAddressCache
import com.regolith.data.smb.SmbActivity
import com.regolith.data.smb.outOfReachCause
import com.regolith.domain.connection.NetworkKind
import com.regolith.domain.connection.OwedWrite
import com.regolith.domain.connection.ShareEvent
import com.regolith.domain.connection.ShareLink
import com.regolith.domain.connection.ShareStatus
import com.regolith.domain.connection.nextLink
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetSocketAddress
import java.net.Socket
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Watches the share being browsed ([ConnectionFocus]) and says whether it
 * can be reached: the connection dot's source of truth. Web analogy: a
 * health-check poller behind a status badge, plus `navigator.onLine` events.
 *
 * **Only while someone is looking.** [status] is cold: the checks run while
 * a screen collects it (with `collectAsStateWithLifecycle`, only while the
 * app is in front) and stop when it goes to the background. No battery is
 * spent watching a share nobody is looking at.
 *
 * **What a check is.** The address is looked up again
 * ([HostAddressCache.refresh]: the owner asked for a new-address check every
 * minute while the app is open), then a bare TCP connect to the SMB port,
 * timed: the same question `ServerAddressResolver` asks, with no login and
 * nothing to clean up. A failed check is tried once more after
 * [RECHECK_MS] before the dot turns grey ([nextLink]).
 *
 * **When.** Every [CONNECTED_EVERY_MS] while connected, every
 * [OUT_EVERY_MS] while out of reach, and at once when the network changes,
 * when a real request finds the share out of reach ([SmbActivity]), or when
 * the sheet's button asks ([checkNow]).
 *
 * **What it does about it.** The server row's reachability is kept in step
 * (`markReachable`/`markUnreachable`, which the Library's out-of-reach card
 * and Settings read too). And when the share comes back with chapter files
 * still owed, they are written at once, instead of at the background job's
 * next try, which is how the phone and the share are kept from disagreeing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class ShareConnectionMonitor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val focus: ConnectionFocus,
    private val serverDao: ServerDao,
    private val syncDao: ChapterSyncDao,
    private val resolver: ServerAddressResolver,
    private val addresses: HostAddressCache,
    private val activity: SmbActivity,
    private val sources: SourceRepository,
    private val chapterSync: ChapterSyncRepository,
) {
    /** The sheet's "Check now" / "Try now". */
    private val pokes = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Drops and returns, said once each, for the message over the pill. */
    val events: SharedFlow<ShareEvent> get() = _events
    private val _events = MutableSharedFlow<ShareEvent>(extraBufferCapacity = 4)

    /** Times the watched share went out of reach since the app opened. */
    private var drops = 0

    /** The share being browsed, watched; null when there is none on the network. */
    val status: Flow<ShareStatus?> = focus.share
        .map { share -> share?.let { it.serverId to it.name } }
        .distinctUntilChanged()
        .flatMapLatest { pair -> if (pair == null) flowOf(null) else watch(pair.first, pair.second) }

    fun checkNow() {
        pokes.tryEmit(Unit)
    }

    private data class Checked(
        val link: ShareLink = ShareLink.CHECKING,
        val address: String? = null,
        val rttMs: Int? = null,
        val outSinceMs: Long? = null,
        val cause: String? = null,
        val answeredAtMs: Long? = null,
    )

    private fun watch(serverId: Long, shareName: String): Flow<ShareStatus> = channelFlow {
        val checked = MutableStateFlow(Checked())
        val wake = Channel<Unit>(Channel.CONFLATED)

        // What can make a check happen before its time.
        launch { pokes.collect { wake.trySend(Unit) } }
        launch {
            networkKind().collect {
                // A new network can mean a new best route: race the addresses again.
                resolver.forget(serverId)
                wake.trySend(Unit)
            }
        }
        launch {
            activity.failures.collect { failure ->
                val host = serverDao.byId(serverId)?.host?.lowercase() ?: return@collect
                if (failure.host == host) wake.trySend(Unit)
            }
        }

        // The checks.
        launch {
            while (isActive) {
                val before = checked.value
                val first = check(serverId)
                val second = if (first.ok) null else { delay(RECHECK_MS); check(serverId) }
                val result = second ?: first
                val link = nextLink(first.ok, second?.ok)
                val now = System.currentTimeMillis()
                checked.value = when (link) {
                    ShareLink.CONNECTED -> Checked(link, result.address ?: first.address, result.rttMs ?: first.rttMs, answeredAtMs = now)
                    else -> before.copy(link = link, outSinceMs = before.outSinceMs ?: now, cause = result.cause, address = result.address ?: before.address)
                }
                onChange(serverId, before.link, link)
                withTimeoutOrNull(if (link == ShareLink.OUT_OF_REACH) OUT_EVERY_MS else CONNECTED_EVERY_MS) { wake.receive() }
            }
        }

        combine(
            checked,
            serverDao.observeAll().map { all -> all.firstOrNull { it.id == serverId } }.distinctUntilChanged(),
            syncDao.observeOwedFor(serverId),
            networkKind(),
        ) { c, server, owed, network ->
            val host = server?.host.orEmpty()
            ShareStatus(
                serverId = serverId,
                serverName = server?.name.orEmpty(),
                shareName = shareName,
                link = c.link,
                host = host,
                port = server?.port ?: 445,
                address = c.address ?: addresses.cached(host),
                rttMs = c.rttMs,
                network = network,
                lastAnsweredAtMs = listOfNotNull(c.answeredAtMs, activity.lastAnsweredAt(host)).maxOrNull(),
                outSinceMs = c.outSinceMs,
                cause = c.cause,
                drops = drops,
                owed = owed.map { OwedWrite(it.fileId, it.name) },
            )
        }.collect { send(it) }
    }

    /** Keep the server row in step, say a drop or a return, and write what was owed on the way back. */
    private suspend fun onChange(serverId: Long, before: ShareLink, after: ShareLink) {
        if (before == after) return
        val name = serverDao.byId(serverId)?.name.orEmpty()
        when (after) {
            ShareLink.CONNECTED -> {
                sources.markReachable(serverId)
                // Back, or found there on opening: either way, anything still
                // owed goes now rather than at the background job's next try.
                val owed = owedCount(serverId)
                if (owed > 0) {
                    chapterSync.syncDirty()
                    val written = owed - owedCount(serverId)
                    Log.i(TAG, "server $serverId answered; $written of $owed owed chapter file(s) written")
                    if (written > 0 || before == ShareLink.OUT_OF_REACH) _events.tryEmit(ShareEvent.Back(name, written))
                } else if (before == ShareLink.OUT_OF_REACH) {
                    _events.tryEmit(ShareEvent.Back(name, written = 0))
                }
            }
            ShareLink.OUT_OF_REACH -> {
                sources.markUnreachable(serverId)
                drops++
                // Said only when it had been there: an app opened away from
                // home starts grey without a message about it.
                if (before == ShareLink.CONNECTED) {
                    _events.tryEmit(ShareEvent.Dropped(name, owed = owedCount(serverId)))
                }
            }
            ShareLink.CHECKING -> Unit
        }
    }

    private suspend fun owedCount(serverId: Long): Int = syncDao.observeOwedFor(serverId).first().size

    private data class Check(val ok: Boolean, val address: String?, val rttMs: Int?, val cause: String?)

    /** One check: look the address up again (keeping the old one if that fails), then time a connect to the SMB port. */
    private suspend fun check(serverId: Long): Check = withContext(Dispatchers.IO) {
        val host = resolver.hostFor(serverId)
        val address = try {
            addresses.refresh(host.host)
        } catch (e: Exception) {
            return@withContext Check(false, null, null, outOfReachCause(e))
        }
        try {
            val started = System.nanoTime()
            Socket().use { it.connect(InetSocketAddress(address, host.port), CONNECT_TIMEOUT_MS) }
            val rtt = ((System.nanoTime() - started) / 1_000_000).toInt().coerceAtLeast(1)
            Check(true, address, rtt, null)
        } catch (e: Exception) {
            addresses.markStale(host.host)
            Log.w(TAG, "check of ${host.host} ($address:${host.port}) failed: $e")
            Check(false, address, null, outOfReachCause(e))
        }
    }

    /** How the phone is online, now and whenever that changes. */
    private fun networkKind(): Flow<NetworkKind> = callbackFlow {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        if (cm == null) {
            trySend(NetworkKind.OTHER); awaitClose(); return@callbackFlow
        }
        fun kindOf(caps: NetworkCapabilities?): NetworkKind = when {
            caps == null -> NetworkKind.NONE
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> NetworkKind.VPN
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkKind.WIFI
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkKind.ETHERNET
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkKind.MOBILE
            else -> NetworkKind.OTHER
        }
        trySend(kindOf(cm.activeNetwork?.let(cm::getNetworkCapabilities)))
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                trySend(kindOf(caps))
            }

            override fun onLost(network: Network) {
                trySend(kindOf(cm.activeNetwork?.let(cm::getNetworkCapabilities)))
            }
        }
        cm.registerDefaultNetworkCallback(callback)
        awaitClose { cm.unregisterNetworkCallback(callback) }
    }.distinctUntilChanged()

    private companion object {
        const val TAG = "Regolith/Connection"

        /** How often a connected share is checked: the owner's minute. */
        const val CONNECTED_EVERY_MS = 60_000L

        /** How often an out-of-reach share is checked, so the dot turns green soon after it is back. */
        const val OUT_EVERY_MS = 15_000L

        /** The pause before a failed check is tried again: one missed answer is not a drop. */
        const val RECHECK_MS = 3_000L

        /** A LAN answers in milliseconds and a VPN relay in a few hundred; past this it is not answering. */
        const val CONNECT_TIMEOUT_MS = 4_000
    }
}
