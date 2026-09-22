package com.regolith.data.repository

import com.regolith.data.credentials.CredentialStore
import com.regolith.data.db.ServerDao
import com.regolith.data.db.ServerEntity
import com.regolith.data.db.ShareDao
import com.regolith.data.db.ShareEntity
import com.regolith.data.db.ShareRootDao
import com.regolith.data.db.ShareRootEntity
import com.regolith.domain.smb.SmbEntry
import kotlinx.coroutines.flow.combine
import com.regolith.domain.model.AuthMode
import com.regolith.domain.model.Server
import com.regolith.domain.model.defaultServerName
import com.regolith.domain.model.serverNameOrDefault
import com.regolith.domain.model.Share
import com.regolith.domain.smb.ParsedSmbAddress
import com.regolith.domain.smb.ServerAccess
import com.regolith.domain.smb.SmbCredentials
import com.regolith.domain.smb.SmbFailure
import com.regolith.domain.smb.SmbShareInfo
import com.regolith.domain.smb.SmbGateway
import com.regolith.domain.smb.SmbHost
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import com.regolith.domain.media.DeviceSource
import com.regolith.domain.media.LocalSource

/**
 * Source servers and their shares: the "Add source server" flow, plus the
 * one place that knows which credentials go with which server.
 *
 * Repository = the layer ViewModels talk to instead of DAOs and the
 * network directly. Web analogy: a data-access service in front of the
 * ORM and the API client.
 */
@Singleton
class SourceRepository @Inject constructor(
    private val gateway: SmbGateway,
    private val serverDao: ServerDao,
    private val shareDao: ShareDao,
    private val shareRootDao: ShareRootDao,
    private val credentialStore: CredentialStore,
    private val deviceLibrary: DeviceLibrary,
    private val addresses: ServerAddressResolver,
) : ServerAccess {
    /**
     * Passwords the user chose NOT to save live here for this process only.
     * A saved password is read back from the CredentialStore on demand.
     */
    private val sessionCredentials = ConcurrentHashMap<Long, SmbCredentials>()

    fun observeServers(): Flow<List<Server>> = serverDao.observeAll().map { list -> list.map { it.toDomain() } }

    fun observeShares(serverId: Long): Flow<List<Share>> = withRoots(shareDao.observeForServer(serverId))

    fun observeEnabledShares(): Flow<List<Share>> = withRoots(shareDao.observeEnabled())

    /**
     * Enabled shares minus "On this device".
     *
     * Library's two tabs are "Network" and "On this device", so a copy that
     * has been adopted into the device source ([DeviceSource]) belongs on
     * the second and must not also be built into the first — it is on the
     * phone, which is the whole point of having adopted it.
     *
     * The demo library is deliberately NOT excluded. It is also local, but
     * it is a sample LIBRARY, meant to be walked like any other.
     */
    fun observeNetworkShares(): Flow<List<Share>> =
        combine(observeEnabledShares(), serverDao.observeAll()) { shares, servers ->
            val deviceIds = servers.filter { DeviceSource.isDevice(it.host) }.map { it.id }.toSet()
            shares.filterNot { it.serverId in deviceIds }
        }

    /** One share with its chosen folders, live; null once it is gone. */
    fun observeShare(shareId: Long): Flow<Share?> =
        combine(shareDao.observe(shareId), shareRootDao.observeForShare(shareId)) { share, roots ->
            share?.toDomain(roots.map { it.relPath })
        }

    /** Share rows joined, in memory, with the folders chosen inside them. */
    private fun withRoots(shares: Flow<List<ShareEntity>>): Flow<List<Share>> =
        combine(shares, shareRootDao.observeAll()) { list, roots ->
            val byShare = roots.groupBy({ it.shareId }, { it.relPath })
            list.map { it.toDomain(byShare[it.id].orEmpty()) }
        }

    suspend fun server(id: Long): Server? = serverDao.byId(id)?.toDomain()

    /**
     * The connect step. Authenticates, lists shares, and only then writes
     * anything: a server row (new or refreshed) and one share row per
     * share the server reported, disabled until the user picks them.
     * Throws [com.regolith.domain.smb.SmbFailure] on any failure.
     *
     * @return the server id to continue the flow with.
     */
    suspend fun connect(address: ParsedSmbAddress, credentials: SmbCredentials, saveCredentials: Boolean): Long {
        val shares = try {
            gateway.listShares(address.host, credentials)
        } catch (e: SmbFailure.AuthFailed) {
            throw e
        } catch (e: SmbFailure) {
            // Enumeration can fail while the share itself is fine: many NAS
            // boxes refuse it to non-admins, and jcifs-ng dials port 445 for
            // the enumeration RPC regardless of the port in the address. If
            // the address names the share, prove it by listing its root and
            // carry on with just that one.
            val named = address.share ?: throw e
            gateway.list(address.host, credentials, named, "")
            listOf(SmbShareInfo(name = named, freeBytes = null, totalBytes = null))
        }
        val now = System.currentTimeMillis()

        val existing = serverDao.byHost(address.host.host, address.host.port)
        val username = (credentials as? SmbCredentials.Password)?.username
        val authMode = if (credentials is SmbCredentials.Guest) AuthMode.GUEST else AuthMode.PASSWORD
        val serverId = if (existing == null) {
            serverDao.insert(
                ServerEntity(
                    name = defaultServerName(address.host),
                    host = address.host.host,
                    port = address.host.port,
                    authMode = authMode.name,
                    username = username,
                    lastSeenAtMs = now,
                    createdAtMs = now,
                ),
            )
        } else {
            serverDao.update(existing.copy(authMode = authMode.name, username = username, lastSeenAtMs = now))
            existing.id
        }

        sessionCredentials[serverId] = credentials
        if (credentials is SmbCredentials.Password) {
            if (saveCredentials) credentialStore.put(serverId, credentials.password) else credentialStore.clear(serverId)
        } else {
            credentialStore.clear(serverId)
        }

        shareDao.upsertAll(
            shares.map { info ->
                ShareEntity(
                    serverId = serverId,
                    name = info.name,
                    // A share named in the address ("smb://tower/media") is what the user meant.
                    enabled = info.name.equals(address.share, ignoreCase = true),
                    freeBytes = info.freeBytes,
                    totalBytes = info.totalBytes,
                    lastScanAtMs = null,
                )
            },
        )
        return serverId
    }

    /**
     * The whole share, or none of it. Either way the folder choice goes:
     * "on" means everything again, and a share that is off has nothing to
     * narrow.
     */
    suspend fun setShareEnabled(shareId: Long, enabled: Boolean) {
        shareRootDao.clearFor(shareId)
        shareDao.setEnabled(shareId, enabled)
    }

    /**
     * Pick one folder inside a share as a library root. Turns the share on
     * if it was off; drops any narrower choice already inside this folder,
     * because the folder now covers it.
     */
    suspend fun addShareRoot(shareId: Long, relPath: String) {
        require(relPath.isNotEmpty()) { "the share itself is not a root; enable the share" }
        shareRootDao.deleteUnder(shareId, "$relPath/%")
        shareRootDao.delete(shareId, relPath)
        shareRootDao.insert(ShareRootEntity(shareId = shareId, relPath = relPath))
        shareDao.setEnabled(shareId, true)
    }

    /** Un-pick a folder. The share stays on even when this was its last root: it goes back to meaning everything. */
    suspend fun removeShareRoot(shareId: Long, relPath: String) = shareRootDao.delete(shareId, relPath)

    /**
     * The folders directly inside [relPath] on a share, straight off the
     * server: the "Choose folders" step lists what is there before anything
     * has been scanned. Names only; the picker has no use for sizes.
     * Throws [SmbFailure].
     */
    suspend fun listFolders(shareId: Long, relPath: String): List<String> {
        val share = checkNotNull(shareDao.byId(shareId)) { "share $shareId" }
        val server = checkNotNull(serverDao.byId(share.serverId)) { "server ${share.serverId}" }
        val entries: List<SmbEntry> = gateway.list(hostFor(server.id), credentialsFor(server.id), share.name, relPath)
        return entries.filter { it.isDirectory }.map { it.name }.sortedBy { it.lowercase() }
    }

    /**
     * Settings and the "Name this server" step: what the user calls this
     * box. Nothing keys off a server's name (identity is its address), so a
     * rename is a single column write with nothing to cascade. Clearing the
     * field is not an error — it means "use the default", and
     * [serverNameOrDefault] puts the derived name back.
     */
    suspend fun renameServer(serverId: Long, typed: String) {
        val server = serverDao.byId(serverId) ?: return
        // Deliberately the ROW and not the resolver: this derives the
        // default display name, and a name that changed depending on which
        // address answered last would be a different name at home and away.
        serverDao.rename(serverId, serverNameOrDefault(typed, SmbHost(server.host, server.port)))
    }

    /**
     * Disconnect a server: its media list goes, its downloads do not.
     *
     * The adoption has to happen FIRST, and that ordering is the whole fix.
     * `transfers` cascades from `media_files`, which cascades from `shares`,
     * which cascades from `servers` — so deleting the server used to delete
     * every download row while leaving the bytes on disk, which is how
     * Settings came to report gigabytes that the On-this-device page could
     * not list. [DeviceLibrary.adopt] re-points the finished copies at a
     * source that is not being deleted, which takes them out of the cascade
     * without touching their ids (so resume points, chapters and artwork
     * come with them), and throws away the half-copied ones that could
     * never be resumed now.
     *
     * @return how many copies were kept, for the confirmation the UI gave.
     */
    suspend fun removeServer(serverId: Long): Int {
        val adopted = deviceLibrary.adopt(serverId)
        credentialStore.clear(serverId)
        sessionCredentials.remove(serverId)
        serverDao.delete(serverId) // shares, folders, files cascade
        return adopted.kept
    }

    /** Credentials for a stored server: this session's, else the saved password, else guest. */
    /** Settings › Chapters: whether Done writes `<basename>.chapters.txt` to this share (P10). */
    suspend fun setShareWriteChapters(shareId: Long, enabled: Boolean) = shareDao.setWriteChapters(shareId, enabled)

    /**
     * Where to reach [serverId] right now.
     *
     * The companion to [credentialsFor], and used the same way: a caller
     * that is about to open an SMB connection asks for the address and the
     * credentials together, rather than reading `host`/`port` off a server
     * row that may name a network this phone is not on any more.
     *
     * A server with one address — which is every server until somebody adds
     * a second — answers straight from its row, so this costs nothing in
     * the common case ([ServerAddressResolver]).
     */
    override suspend fun hostFor(serverId: Long): SmbHost = addresses.hostFor(serverId)

    /** For ExoPlayer's loader thread, like [credentialsForBlocking]. Never call on the main thread. */
    fun hostForBlocking(serverId: Long): SmbHost = runBlocking { hostFor(serverId) }

    override suspend fun credentialsFor(serverId: Long): SmbCredentials {
        sessionCredentials[serverId]?.let { return it }
        val server = serverDao.byId(serverId) ?: return SmbCredentials.Guest
        val creds = if (server.authMode == AuthMode.PASSWORD.name && server.username != null) {
            credentialStore.get(serverId)?.let { SmbCredentials.Password(server.username, it) } ?: SmbCredentials.Guest
        } else {
            SmbCredentials.Guest
        }
        sessionCredentials[serverId] = creds
        return creds
    }

    /** For ExoPlayer's loader thread, which is not a coroutine. Never call on the main thread. */
    fun credentialsForBlocking(serverId: Long): SmbCredentials = runBlocking { credentialsFor(serverId) }

    // --- Reachability. Every SMB caller reports here so Library, Home and
    // the transfers agree on "out of reach", and "Try again" has one thing to do.

    suspend fun markReachable(serverId: Long) = serverDao.markReachable(serverId, System.currentTimeMillis())

    suspend fun markUnreachable(serverId: Long) = serverDao.markUnreachable(serverId, System.currentTimeMillis())

    /** "Try again": one cheap listing of the first enabled share's root. Returns true when the server answered. */
    suspend fun probeReachable(serverId: Long): Boolean {
        val server = serverDao.byId(serverId) ?: return false
        // A source on the phone has no host; it is never out of reach.
        if (LocalSource.isLocal(server.host)) return true
        val share = shareDao.observeForServer(serverId).first().firstOrNull { it.enabled } ?: return false
        return try {
            gateway.list(hostFor(serverId), credentialsFor(serverId), share.name, "")
            markReachable(serverId)
            true
        } catch (e: SmbFailure) {
            markUnreachable(serverId)
            false
        }
    }

    private fun ServerEntity.toDomain() = Server(
        id = id,
        name = name,
        host = SmbHost(host, port),
        authMode = AuthMode.valueOf(authMode),
        username = username,
        lastSeenAtMs = lastSeenAtMs,
        unreachableSinceMs = unreachableSinceMs,
    )

    private fun ShareEntity.toDomain(roots: List<String> = emptyList()) =
        Share(id = id, serverId = serverId, name = name, enabled = enabled, freeBytes = freeBytes, lastScanAtMs = lastScanAtMs, roots = roots, writeChapters = writeChapters)
}
