package com.regolith.data.smb

import com.regolith.domain.smb.SeekableByteSource
import com.regolith.domain.smb.SmbCredentials
import com.regolith.domain.smb.SmbEntry
import com.regolith.domain.smb.SmbFailure
import com.regolith.domain.smb.SmbGateway
import com.regolith.domain.smb.SmbHost
import com.regolith.domain.smb.SmbShareInfo
import jcifs.CIFSContext
import jcifs.CIFSException
import jcifs.SmbConstants
import jcifs.config.PropertyConfiguration
import jcifs.context.BaseContext
import jcifs.smb.NtStatus
import jcifs.smb.NtlmPasswordAuthenticator
import jcifs.smb.SmbAuthException
import jcifs.smb.SmbException
import jcifs.smb.SmbFile
import jcifs.smb.SmbRandomAccessFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The one place jcifs-ng is used (guardrail G1). Everything above this
 * class speaks [SmbGateway]; if jcifs-ng ever has to be swapped for
 * codelibs/jcifs or SMBJ, this file is the change.
 *
 * Session pooling: one [CIFSContext] per (host, credentials). jcifs-ng
 * keeps the TCP transport and the authenticated session alive inside the
 * context, so repeated listings and player seeks do not reconnect.
 *
 * Every method blocks on the network. The suspend ones hop to
 * `Dispatchers.IO`; [open] is called by ExoPlayer on its own loader thread.
 */
@Singleton
class JcifsGateway @Inject constructor() : SmbGateway {

    private val baseContext: CIFSContext by lazy {
        val props = Properties().apply {
            // SMB2 minimum: SMB1 is off by default on every modern NAS and
            // Windows, and jcifs-ng's SMB1 path is the slow one anyway.
            setProperty("jcifs.smb.client.minVersion", "SMB202")
            setProperty("jcifs.smb.client.maxVersion", "SMB311")
            setProperty("jcifs.smb.client.connTimeout", "8000")
            setProperty("jcifs.smb.client.responseTimeout", "15000")
            setProperty("jcifs.smb.client.soTimeout", "20000")
            // Plain DNS: NetBIOS broadcast lookups add seconds on Wi-Fi and
            // the user types IPs or DNS names anyway.
            setProperty("jcifs.resolveOrder", "DNS")
            setProperty("jcifs.smb.client.dfs.disabled", "true")
            // Names on the wire are UTF-8 on every server we care about.
            setProperty("jcifs.encoding", "UTF-8")
        }
        BaseContext(PropertyConfiguration(props))
    }

    private val contexts = ConcurrentHashMap<Pair<SmbHost, SmbCredentials>, CIFSContext>()

    private fun context(host: SmbHost, credentials: SmbCredentials): CIFSContext =
        contexts.getOrPut(host to credentials) {
            val auth = when (credentials) {
                SmbCredentials.Guest -> NtlmPasswordAuthenticator(NtlmPasswordAuthenticator.AuthenticationType.GUEST)
                is SmbCredentials.Password -> NtlmPasswordAuthenticator(
                    credentials.domain,
                    credentials.username,
                    credentials.password,
                )
            }
            baseContext.withCredentials(auth)
        }

    override suspend fun listShares(host: SmbHost, credentials: SmbCredentials): List<SmbShareInfo> =
        withContext(Dispatchers.IO) {
            wrap(host, "/") {
                val ctx = context(host, credentials)
                val root = SmbFile(urlFor(host, null, ""), ctx)
                root.listFiles()
                    .filter { it.type == SmbConstants.TYPE_SHARE }
                    .map { it.name.trimEnd('/') }
                    // Administrative and hidden shares (C$, IPC$, print$) are never media.
                    .filterNot { it.endsWith("$") }
                    .sorted()
                    .map { name ->
                        val free = runCatching { SmbFile(urlFor(host, name, ""), ctx).diskFreeSpace }.getOrNull()
                        SmbShareInfo(name = name, freeBytes = free, totalBytes = null)
                    }
            }
        }

    override suspend fun list(
        host: SmbHost,
        credentials: SmbCredentials,
        share: String,
        relPath: String,
    ): List<SmbEntry> = withContext(Dispatchers.IO) {
        wrap(host, "$share/$relPath") {
            val dir = SmbFile(urlFor(host, share, relPath), context(host, credentials))
            dir.listFiles().mapNotNull { f ->
                val name = f.name.trimEnd('/')
                if (name.startsWith(".")) return@mapNotNull null // .DS_Store, @eaDir-style junk
                val isDir = f.isDirectory
                SmbEntry(
                    name = name,
                    isDirectory = isDir,
                    sizeBytes = if (isDir) 0 else f.length(),
                    modifiedAtMs = f.lastModified(),
                )
            }
        }
    }

    override fun open(host: SmbHost, credentials: SmbCredentials, share: String, relPath: String): SeekableByteSource =
        wrap(host, "$share/$relPath") {
            val file = SmbFile(urlFor(host, share, relPath), context(host, credentials))
            JcifsByteSource(file.openRandomAccess("r"), file.length())
        }

    /** Raw smb:// URL. jcifs-ng takes path characters as-is (no percent-decoding), so nothing is encoded. */
    private fun urlFor(host: SmbHost, share: String?, relPath: String): String = buildString {
        append("smb://").append(host.host)
        if (host.port != SmbHost.DEFAULT_PORT) append(':').append(host.port)
        append('/')
        if (share != null) {
            append(share).append('/')
            if (relPath.isNotEmpty()) append(relPath.trimEnd('/')).append('/')
        }
    }

    /** Translate jcifs failures into the [SmbFailure]s the UI has screens for. */
    private inline fun <T> wrap(host: SmbHost, path: String, block: () -> T): T = try {
        block()
    } catch (e: SmbAuthException) {
        throw SmbFailure.AuthFailed(e)
    } catch (e: SmbException) {
        throw when (e.ntStatus) {
            NtStatus.NT_STATUS_LOGON_FAILURE,
            NtStatus.NT_STATUS_ACCESS_DENIED,
            NtStatus.NT_STATUS_ACCOUNT_DISABLED,
            NtStatus.NT_STATUS_WRONG_PASSWORD,
            -> SmbFailure.AuthFailed(e)
            NtStatus.NT_STATUS_OBJECT_NAME_NOT_FOUND,
            NtStatus.NT_STATUS_OBJECT_PATH_NOT_FOUND,
            NtStatus.NT_STATUS_BAD_NETWORK_NAME,
            NtStatus.NT_STATUS_NO_SUCH_FILE,
            -> SmbFailure.NotFound(path, e)
            else -> if (e.isUnreachable()) SmbFailure.Unreachable(host.host, e) else SmbFailure.Other(e.message ?: "SMB error", e)
        }
    } catch (e: CIFSException) {
        throw if (e.isUnreachable()) SmbFailure.Unreachable(host.host, e) else SmbFailure.Other(e.message ?: "SMB error", e)
    } catch (e: UnknownHostException) {
        throw SmbFailure.Unreachable(host.host, e)
    }

    private fun Throwable.isUnreachable(): Boolean {
        var t: Throwable? = this
        while (t != null) {
            if (t is UnknownHostException || t is ConnectException || t is SocketTimeoutException) return true
            if (t is jcifs.util.transport.TransportException) return true
            t = t.cause
        }
        return false
    }
}

/**
 * [SeekableByteSource] over a jcifs random-access handle. jcifs tracks a
 * file pointer, so a read at a new offset is a seek then a read; seeks are
 * free (they only move the pointer), the next read is what hits the wire.
 */
private class JcifsByteSource(
    private val file: SmbRandomAccessFile,
    override val size: Long,
) : SeekableByteSource {
    private var position = 0L

    @Synchronized
    override fun readAt(offset: Long, dst: ByteArray, dstOffset: Int, length: Int): Int {
        if (length == 0) return 0
        if (offset >= size) return -1
        if (offset != position) {
            file.seek(offset)
            position = offset
        }
        val n = file.read(dst, dstOffset, length)
        if (n <= 0) return -1
        position += n
        return n
    }

    @Synchronized
    override fun close() {
        runCatching { file.close() }
    }
}
