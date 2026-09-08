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
    var acceptedCredentials: SmbCredentials? = null
    var reachable = true
    var openCount = 0

    fun addFile(share: String, relPath: String, bytes: ByteArray) {
        files.getOrPut(share) { mutableMapOf() }[relPath] = bytes
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
                entries += SmbEntry(rest, isDirectory = false, sizeBytes = bytes.size.toLong(), modifiedAtMs = 1_700_000_000_000)
            } else {
                dirs += rest.substring(0, slash)
            }
        }
        entries += dirs.map { SmbEntry(it, isDirectory = true, sizeBytes = 0, modifiedAtMs = 1_700_000_000_000) }
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
}
