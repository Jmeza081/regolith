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
import android.util.Log
import java.net.ConnectException
import java.security.Security
import org.bouncycastle.jce.provider.BouncyCastleProvider
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

    private companion object {
        const val TAG = "Regolith/SMB"
    }

    /**
     * Loading the full BouncyCastle provider takes seconds on a cold start
     * (thousands of classes), and this singleton is built while the app is
     * still starting. So the install runs on its own thread and every
     * network entry point joins it before the first handshake, which keeps
     * the splash short without ever letting NTLM run before MD4 exists.
     */
    private val bouncyCastleInstall: Thread = Thread({ installFullBouncyCastle() }, "regolith-bc-install").apply { start() }

    /**
     * NTLM authentication hashes the password with MD4. Android ships a
     * trimmed BouncyCastle registered under the name "BC" that has no MD4,
     * and `Security.addProvider` ignores jcifs-ng's full BouncyCastle
     * because the name is taken. So: drop Android's, register the full one.
     * Without this every password login fails with
     * "NoSuchAlgorithmException: no such algorithm: MD4 for provider BC".
     */
    private fun installFullBouncyCastle() {
        val existing = Security.getProvider("BC")
        if (existing != null && existing.javaClass.name == BouncyCastleProvider::class.java.name) return
        Security.removeProvider("BC")
        Security.insertProviderAt(BouncyCastleProvider(), 1)
        Log.i(TAG, "Replaced Android's BC provider with bundled BouncyCastle ${BouncyCastleProvider().version}")
    }

    private val baseProps: Properties by lazy {
        Properties().apply {
            // SMB2 minimum: SMB1 is off by default on every modern NAS and
            // Windows, and jcifs-ng's SMB1 path is the slow one anyway.
            setProperty("jcifs.smb.client.minVersion", "SMB202")
            setProperty("jcifs.smb.client.connTimeout", "5000")
            setProperty("jcifs.smb.client.responseTimeout", "15000")
            setProperty("jcifs.smb.client.soTimeout", "20000")
            // Plain DNS: NetBIOS broadcast lookups add seconds on Wi-Fi and
            // the user types IPs or DNS names anyway.
            setProperty("jcifs.resolveOrder", "DNS")
            setProperty("jcifs.smb.client.dfs.disabled", "true")
            // Names on the wire are UTF-8 on every server we care about.
            setProperty("jcifs.encoding", "UTF-8")
            // Throughput. jcifs-ng leaves Nagle on by default, so every 64 KB
            // read request waits for a delayed ACK (~100 ms): measured at
            // ~640 KB/s over loopback before this. Bigger buffers let each
            // SMB2 read carry up to 1 MB.
            setProperty("jcifs.smb.client.tcpNoDelay", "true")
            setProperty("jcifs.smb.client.rcv_buf_size", "1048576")
            setProperty("jcifs.smb.client.snd_buf_size", "1048576")
        }
    }

    /**
     * Highest SMB dialect to offer, tried in order. jcifs-ng's SMB 3.1.1
     * handshake is rejected by some servers (pre-auth integrity, encryption
     * requirements it cannot meet); 3.0.2 and 2.1 are near-universal. The
     * first dialect that gets past negotiation is pinned per host.
     */
    private val dialectLadder = listOf("SMB311", "SMB302", "SMB210")
    private val pinnedDialect = ConcurrentHashMap<SmbHost, String>()

    private data class ContextKey(val host: SmbHost, val credentials: SmbCredentials, val maxDialect: String, val lenientSigning: Boolean = false)

    /** Hosts whose IPC$ signing failed validation and were accepted without enforcement. */
    private val lenientHosts = java.util.Collections.newSetFromMap(ConcurrentHashMap<SmbHost, Boolean>())

    private val contexts = ConcurrentHashMap<ContextKey, CIFSContext>()

    private fun context(host: SmbHost, credentials: SmbCredentials, maxDialect: String, lenientSigning: Boolean = false): CIFSContext {
        bouncyCastleInstall.join()
        return contexts.getOrPut(ContextKey(host, credentials, maxDialect, lenientSigning)) {
            val auth = when (credentials) {
                SmbCredentials.Guest -> NtlmPasswordAuthenticator(NtlmPasswordAuthenticator.AuthenticationType.GUEST)
                is SmbCredentials.Password -> NtlmPasswordAuthenticator(
                    // Empty, not null: jcifs-ng would otherwise fill in its own default domain.
                    credentials.domain ?: "",
                    credentials.username,
                    credentials.password,
                )
            }
            BaseContext(PropertyConfiguration(mergedProps(maxDialect, lenientSigning))).withCredentials(auth)
        }
    }

    private fun mergedProps(maxDialect: String, lenientSigning: Boolean): Properties = Properties().apply {
        putAll(baseProps)
        setProperty("jcifs.smb.client.maxVersion", maxDialect)
        if (lenientSigning) {
            // Some servers (old NAS firmware, minimal SMB implementations)
            // produce signatures jcifs-ng cannot validate. Only after that
            // exact failure do we stop insisting on a signed IPC$ channel.
            setProperty("jcifs.smb.client.ipcSigningEnforced", "false")
            setProperty("jcifs.smb.client.signingEnforced", "false")
        }
    }

    /**
     * Run [block] with the pinned dialect, or walk the ladder until one
     * negotiates. Only non-auth failures move down the ladder: a wrong
     * password is wrong on every dialect.
     */
    private inline fun <T> withDialect(host: SmbHost, credentials: SmbCredentials, path: String, block: (CIFSContext) -> T): T {
        // Guest sessions have no session key and can never sign; insisting on
        // a signed IPC$ channel would refuse every guest share on the planet.
        val lenient = credentials is SmbCredentials.Guest || host in lenientHosts
        pinnedDialect[host]?.let { d -> return wrap(host, path, d) { block(context(host, credentials, d, lenient)) } }
        var last: SmbFailure? = null
        for (d in dialectLadder) {
            try {
                val result = try {
                    wrap(host, path, d) { block(context(host, credentials, d, lenient)) }
                } catch (e: SmbFailure) {
                    if (lenient || !e.isSignatureFailure()) throw e
                    Log.w(TAG, "$d signing failed for ${host.host}; retrying without enforced signing")
                    wrap(host, path, d) { block(context(host, credentials, d, lenientSigning = true)) }
                        .also { lenientHosts += host }
                }
                pinnedDialect[host] = d
                return result
            } catch (e: SmbFailure.AuthFailed) {
                throw e
            } catch (e: SmbFailure) {
                // A refused/timed-out TCP connection or a name that does not
                // resolve is the same on every dialect: stop immediately.
                if (e.isTcpLevel()) throw e
                Log.w(TAG, "$d failed for ${host.host}: ${e.detail ?: e.message}")
                last = e
            }
        }
        throw checkNotNull(last)
    }

    private fun Throwable.isSignatureFailure(): Boolean = generateSequence(this) { it.cause }.any { t ->
        val m = t.message ?: return@any false
        m.contains("Signature validation failed", ignoreCase = true) || m.contains("signing", ignoreCase = true)
    }

    /**
     * Known limit: jcifs-ng enumerates shares over a DCERPC pipe whose
     * transport is keyed by host name only, so on a non-default port this
     * dials 445 and fails. `SourceRepository.connect` falls back to the
     * share named in the address.
     */
    override suspend fun listShares(host: SmbHost, credentials: SmbCredentials): List<SmbShareInfo> =
        withContext(Dispatchers.IO) {
            withDialect(host, credentials, "/") { ctx ->
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
        withDialect(host, credentials, "$share/$relPath") { ctx ->
            val dir = SmbFile(urlFor(host, share, relPath), ctx)
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

    /**
     * Read a file on the share.
     *
     * The `isFile` check is not belt-and-braces: jcifs-ng opens read-only
     * handles with create-if-missing (`SmbRandomAccessFile(file, "r")` sets
     * O_CREAT | O_RDONLY), so asking for a path that is not there CREATES an
     * empty file on the share — how 0-byte `.chapters.txt` files appeared
     * beside films that never had chapters. Unlike a local `File`, a missing
     * name here is not an error the library reports; we have to look first.
     * The URL is built without the trailing slash `urlFor` adds, so `isFile`
     * is asked about a file rather than a directory.
     */
    override fun open(host: SmbHost, credentials: SmbCredentials, share: String, relPath: String): SeekableByteSource =
        withDialect(host, credentials, "$share/$relPath") { ctx ->
            val file = SmbFile(fileUrl(host, share, relPath), ctx)
            if (!file.isFile) throw SmbFailure.NotFound("$share/$relPath")
            JcifsByteSource(file.openRandomAccess("r"), file.length())
        }

    // --- Writing (P10): three methods, one caller (`SidecarWriter`), one
    // file name. The URL is built WITHOUT the trailing slash `urlFor` adds:
    // jcifs reads an existing file through either form, but creates a new
    // name as a directory when the URL ends in `/`.

    override suspend fun write(host: SmbHost, credentials: SmbCredentials, share: String, relPath: String, bytes: ByteArray): Long =
        withContext(Dispatchers.IO) {
            withDialect(host, credentials, "$share/$relPath") { ctx ->
                SmbFile(fileUrl(host, share, relPath), ctx).openOutputStream().use { it.write(bytes) }
                // A fresh handle: the one that wrote may still carry cached attributes.
                SmbFile(fileUrl(host, share, relPath), ctx).lastModified()
            }
        }

    override suspend fun rename(host: SmbHost, credentials: SmbCredentials, share: String, fromRelPath: String, toRelPath: String) =
        withContext(Dispatchers.IO) {
            withDialect(host, credentials, "$share/$fromRelPath") { ctx ->
                SmbFile(fileUrl(host, share, fromRelPath), ctx).renameTo(SmbFile(fileUrl(host, share, toRelPath), ctx), true)
            }
        }

    override suspend fun delete(host: SmbHost, credentials: SmbCredentials, share: String, relPath: String) =
        withContext(Dispatchers.IO) {
            withDialect(host, credentials, "$share/$relPath") { ctx ->
                val file = SmbFile(fileUrl(host, share, relPath), ctx)
                if (file.exists()) file.delete()
            }
        }

    private fun fileUrl(host: SmbHost, share: String, relPath: String): String = urlFor(host, share, relPath).trimEnd('/')

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
    private inline fun <T> wrap(host: SmbHost, path: String, dialect: String, block: () -> T): T = try {
        block()
    } catch (e: SmbAuthException) {
        // jcifs raises its auth exception for ACCESS_DENIED too — "signed in,
        // but not allowed to do this" — which for a write is a read-only
        // share, not a bad password.
        if (e.ntStatus == NtStatus.NT_STATUS_ACCESS_DENIED) throw SmbFailure.Forbidden(path, e, detail(e, dialect))
        throw SmbFailure.AuthFailed(e, detail(e, dialect))
    } catch (e: SmbException) {
        // The full cause chain: jcifs folds transport-thread failures into one status code.
        Log.w(TAG, "SMB failure on $dialect for ${host.host}$path", e)
        throw when (e.ntStatus) {
            NtStatus.NT_STATUS_LOGON_FAILURE,
            NtStatus.NT_STATUS_ACCOUNT_DISABLED,
            NtStatus.NT_STATUS_WRONG_PASSWORD,
            NtStatus.NT_STATUS_ACCOUNT_RESTRICTION,
            NtStatus.NT_STATUS_INVALID_LOGON_HOURS,
            NtStatus.NT_STATUS_PASSWORD_EXPIRED,
            -> SmbFailure.AuthFailed(e, detail(e, dialect))
            // Signed in, but this request was refused: not the same thing as a bad password.
            NtStatus.NT_STATUS_ACCESS_DENIED -> SmbFailure.Forbidden(path, e, detail(e, dialect))
            NtStatus.NT_STATUS_OBJECT_NAME_NOT_FOUND,
            NtStatus.NT_STATUS_OBJECT_PATH_NOT_FOUND,
            NtStatus.NT_STATUS_BAD_NETWORK_NAME,
            NtStatus.NT_STATUS_NO_SUCH_FILE,
            -> SmbFailure.NotFound(path, e, detail(e, dialect))
            else -> if (e.isUnreachable()) SmbFailure.Unreachable(host.host, e, detail(e, dialect)) else SmbFailure.Other(e.message ?: "SMB error", e, detail(e, dialect))
        }
    } catch (e: CIFSException) {
        Log.w(TAG, "CIFS failure on $dialect for ${host.host}$path", e)
        throw if (e.isUnreachable()) SmbFailure.Unreachable(host.host, e, detail(e, dialect)) else SmbFailure.Other(e.message ?: "SMB error", e, detail(e, dialect))
    } catch (e: UnknownHostException) {
        throw SmbFailure.Unreachable(host.host, e, "DNS lookup failed · $dialect")
    }

    /** "NT_STATUS_ACCESS_DENIED (0xC0000022) · SMB302": what to read off the error card when debugging. */
    private fun detail(e: Throwable, dialect: String): String {
        val status = (e as? SmbException)?.ntStatus?.let { code ->
            val name = NtStatus.NT_STATUS_CODES.indexOf(code).takeIf { it >= 0 }?.let { NtStatus.NT_STATUS_MESSAGES[it] }
            String.format("0x%08X", code) + (name?.let { " $it" } ?: "")
        }
        val root = generateSequence<Throwable>(e) { it.cause }.last()
        val msg = root.message?.take(200)
        return listOfNotNull(status ?: root::class.java.simpleName, msg?.takeIf { it != status }, dialect).joinToString(" · ")
    }

    private fun Throwable.isTcpLevel(): Boolean =
        generateSequence(this) { it.cause }.any { it is UnknownHostException || it is ConnectException || it is SocketTimeoutException }

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
