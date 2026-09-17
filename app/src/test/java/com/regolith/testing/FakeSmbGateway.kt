package com.regolith.testing

import com.regolith.domain.smb.SeekableByteSource
import com.regolith.domain.smb.SmbCredentials
import com.regolith.domain.smb.SmbEntry
import com.regolith.domain.smb.SmbFailure
import com.regolith.domain.smb.SmbGateway
import com.regolith.domain.smb.SmbHost
import com.regolith.domain.smb.SmbShareInfo

/**
 * In-memory SMB server for JVM tests: a map of share -> path -> bytes.
 * Directories are implied by the file paths. Set [acceptedCredentials] to
 * make anything else fail with [SmbFailure.AuthFailed]; set [reachable]
 * false to fail with [SmbFailure.Unreachable].
 */
class FakeSmbGateway : SmbGateway {
    val files = mutableMapOf<String, MutableMap<String, ByteArray>>() // share -> relPath -> bytes
    /** Modified times, share -> relPath -> ms; [DEFAULT_MTIME] when unset. */
    val mtimes = mutableMapOf<String, MutableMap<String, Long>>()
    var acceptedCredentials: SmbCredentials? = null
    var reachable = true
    var openCount = 0
    /** Every write, rename and delete fails with [SmbFailure.Forbidden], the way a read-only share answers. */
    var readOnly = false
    /** A server that refuses to rename onto an existing name (some NAS do); the writer must delete first. */
    var renameOverExistingFails = false

    /**
     * The share drops after this many renames, the way a Wi-Fi blip does
     * mid-batch. There is no other way to reach the case the SMB probe
     * proved matters most: everything already renamed stays renamed.
     */
    var unreachableAfterRenames: Int? = null
    var renameCount = 0
    /** Each write's clock: the next write gets this time, then it advances by a second. */
    var clockMs = DEFAULT_MTIME + 1_000
    val writes = mutableListOf<String>()

    fun addFile(share: String, relPath: String, bytes: ByteArray, modifiedAtMs: Long = DEFAULT_MTIME) {
        files.getOrPut(share) { mutableMapOf() }[relPath] = bytes
        mtimes.getOrPut(share) { mutableMapOf() }[relPath] = modifiedAtMs
    }

    fun mtime(share: String, relPath: String): Long = mtimes[share]?.get(relPath) ?: DEFAULT_MTIME

    private fun checkWritable(host: SmbHost, credentials: SmbCredentials, share: String) {
        check(host, credentials)
        if (!files.containsKey(share)) throw SmbFailure.NotFound(share)
        if (readOnly) throw SmbFailure.Forbidden("$share is read-only")
    }

    override suspend fun write(host: SmbHost, credentials: SmbCredentials, share: String, relPath: String, bytes: ByteArray): Long {
        checkWritable(host, credentials, share)
        val at = clockMs
        clockMs += 1_000
        files.getValue(share)[relPath] = bytes.copyOf()
        mtimes.getOrPut(share) { mutableMapOf() }[relPath] = at
        writes += "$share/$relPath"
        return at
    }

    override suspend fun rename(host: SmbHost, credentials: SmbCredentials, share: String, fromRelPath: String, toRelPath: String, replace: Boolean) {
        unreachableAfterRenames?.let { limit -> if (renameCount >= limit) throw SmbFailure.Unreachable(host.host) }
        renameCount++
        checkWritable(host, credentials, share)
        val all = files.getValue(share)
        val bytes = all[fromRelPath] ?: throw SmbFailure.NotFound("$share/$fromRelPath")
        // Without [replace] the real server refuses rather than overwriting,
        // and so must this: the whole point of the flag is that a clobber
        // is never silent.
        if ((!replace || renameOverExistingFails) && all.containsKey(toRelPath)) throw SmbFailure.Other("$toRelPath exists")
        all.remove(fromRelPath); all[toRelPath] = bytes
        val m = mtimes.getOrPut(share) { mutableMapOf() }
        m[toRelPath] = m.remove(fromRelPath) ?: DEFAULT_MTIME
    }

    override suspend fun delete(host: SmbHost, credentials: SmbCredentials, share: String, relPath: String) {
        checkWritable(host, credentials, share)
        files.getValue(share).remove(relPath)
        mtimes[share]?.remove(relPath)
    }

    private fun check(host: SmbHost, credentials: SmbCredentials) {
        if (!reachable) throw SmbFailure.Unreachable(host.host)
        acceptedCredentials?.let { if (it != credentials) throw SmbFailure.AuthFailed() }
    }

    override suspend fun listShares(host: SmbHost, credentials: SmbCredentials): List<SmbShareInfo> {
        check(host, credentials)
        return files.keys.sorted().map { SmbShareInfo(it, freeBytes = 1L shl 40, totalBytes = null) }
    }

    override suspend fun list(host: SmbHost, credentials: SmbCredentials, share: String, relPath: String): List<SmbEntry> {
        check(host, credentials)
        val all = files[share] ?: throw SmbFailure.NotFound(share)
        val prefix = if (relPath.isEmpty()) "" else "$relPath/"
        val dirs = mutableSetOf<String>()
        val entries = mutableListOf<SmbEntry>()
        for ((path, bytes) in all) {
            if (!path.startsWith(prefix)) continue
            val rest = path.removePrefix(prefix)
            val slash = rest.indexOf('/')
            if (slash < 0) {
                entries += SmbEntry(rest, isDirectory = false, sizeBytes = bytes.size.toLong(), modifiedAtMs = mtime(share, path))
            } else {
                dirs += rest.substring(0, slash)
            }
        }
        entries += dirs.map { SmbEntry(it, isDirectory = true, sizeBytes = 0, modifiedAtMs = DEFAULT_MTIME) }
        return entries
    }

    override fun open(host: SmbHost, credentials: SmbCredentials, share: String, relPath: String): SeekableByteSource {
        check(host, credentials)
        val bytes = files[share]?.get(relPath) ?: throw SmbFailure.NotFound("$share/$relPath")
        openCount++
        return ByteArraySource(bytes)
    }

    class ByteArraySource(private val bytes: ByteArray) : SeekableByteSource {
        override val size: Long get() = bytes.size.toLong()
        var closed = false

        override fun readAt(offset: Long, dst: ByteArray, dstOffset: Int, length: Int): Int {
            if (length == 0) return 0
            if (offset >= bytes.size) return -1
            // Deliberately short reads (max 7 bytes) so callers that don't loop fail loudly.
            val n = minOf(length, 7, (bytes.size - offset).toInt())
            System.arraycopy(bytes, offset.toInt(), dst, dstOffset, n)
            return n
        }

        override fun close() {
            closed = true
        }
    }

    companion object {
        const val DEFAULT_MTIME = 1_700_000_000_000L
    }
}
