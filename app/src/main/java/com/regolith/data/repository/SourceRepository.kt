package com.regolith.data.repository

import com.regolith.data.credentials.CredentialStore
import com.regolith.data.db.ServerDao
import com.regolith.data.db.ServerEntity
import com.regolith.data.db.ShareDao
import com.regolith.data.db.ShareEntity
import com.regolith.domain.model.AuthMode
import com.regolith.domain.model.Server
import com.regolith.domain.model.Share
import com.regolith.domain.smb.ParsedSmbAddress
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
import com.regolith.domain.media.DemoSource

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
    private val credentialStore: CredentialStore,
) {
    /**
     * Passwords the user chose NOT to save live here for this process only.
     * A saved password is read back from the CredentialStore on demand.
     */
    private val sessionCredentials = ConcurrentHashMap<Long, SmbCredentials>()

    fun observeServers(): Flow<List<Server>> = serverDao.observeAll().map { list -> list.map { it.toDomain() } }

    fun observeShares(serverId: Long): Flow<List<Share>> =
        shareDao.observeForServer(serverId).map { list -> list.map { it.toDomain() } }

    fun observeEnabledShares(): Flow<List<Share>> = shareDao.observeEnabled().map { list -> list.map { it.toDomain() } }

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
                    name = displayNameFor(address.host),
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

    suspend fun setShareEnabled(shareId: Long, enabled: Boolean) = shareDao.setEnabled(shareId, enabled)

    suspend fun removeServer(serverId: Long) {
        credentialStore.clear(serverId)
        sessionCredentials.remove(serverId)
        serverDao.delete(serverId) // shares, folders, files cascade
    }

    /** Credentials for a stored server: this session's, else the saved password, else guest. */
    suspend fun credentialsFor(serverId: Long): SmbCredentials {
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
        // The demo library has no host; it is never out of reach.
        if (DemoSource.isDemo(server.host)) return true
        val share = shareDao.observeForServer(serverId).first().firstOrNull { it.enabled } ?: return false
        return try {
            gateway.list(SmbHost(server.host, server.port), credentialsFor(serverId), share.name, "")
            markReachable(serverId)
            true
        } catch (e: SmbFailure) {
            markUnreachable(serverId)
            false
        }
    }

    /** "TOWER" for tower.local, the address itself for an IP. */
    private fun displayNameFor(host: SmbHost): String {
        val h = host.host
        val isIp = h.all { it.isDigit() || it == '.' } || h.contains(':')
        return if (isIp) h else h.substringBefore('.').uppercase().ifBlank { h }
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

    private fun ShareEntity.toDomain() = Share(id = id, serverId = serverId, name = name, enabled = enabled, freeBytes = freeBytes, lastScanAtMs = lastScanAtMs)
}
