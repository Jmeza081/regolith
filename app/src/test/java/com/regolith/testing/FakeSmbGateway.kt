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
 * Directories are implied by the file paths, except empty ones, which live
 * in [dirs] because nothing else can imply them. Set [acceptedCredentials] to
 * make anything else fail with [SmbFailure.AuthFailed]; set [reachable]
 * false to fail with [SmbFailure.Unreachable].
 */
class FakeSmbGateway : SmbGateway {
    val files = mutableMapOf<String, MutableMap<String, ByteArray>>() // share -> relPath -> bytes
    /**
     * Directories that exist in their own right, share -> relPath. A folder
     * holding files is implied by their paths and need not be in here; one
     * that is EMPTY has no other way to exist, which is the whole point of
     * `mkdir`.
     */
    val dirs = mutableMapOf<String, MutableSet<String>>()
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
        addShare(share)
        files.getValue(share)[relPath] = bytes
        mtimes.getOrPut(share) { mutableMapOf() }[relPath] = modifiedAtMs
    }

    /** A folder that is there before anything is in it. */
    fun addDir(share: String, relPath: String) {
        addShare(share)
        dirs.getOrPut(share) { mutableSetOf() } += relPath
    }

    /**
     * An empty share. A share exists here iff it is a key in [files], and a
     * test that only ever makes FOLDERS would otherwise be talking to a
     * server that says the whole share is missing.
     */
    fun addShare(share: String) {
        files.getOrPut(share) { mutableMapOf() }
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
        // A directory rename is ONE operation on a real server: the whole
        // subtree arrives or none of it does. Here that is a prefix swap.
        if (isDir(share, fromRelPath)) return renameDir(share, fromRelPath, toRelPath)
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

    override suspend fun mkdir(host: SmbHost, credentials: SmbCredentials, share: String, relPath: String) {
        checkWritable(host, credentials, share)
        if (isDir(share, relPath) || files.getValue(share).containsKey(relPath)) throw SmbFailure.Other("$relPath exists")
        dirs.getOrPut(share) { mutableSetOf() } += relPath
    }

    override suspend fun deleteFolder(host: SmbHost, credentials: SmbCredentials, share: String, relPath: String) {
        checkWritable(host, credentials, share)
        val prefix = "$relPath/"
        files.getValue(share).keys.filter { it.startsWith(prefix) }.forEach {
            files.getValue(share).remove(it)
            mtimes[share]?.remove(it)
        }
        dirs[share]?.removeAll { it == relPath || it.startsWith(prefix) }
    }

    /** Is there a folder at this path — explicitly made, or implied by a file under it? */
    fun isDir(share: String, relPath: String): Boolean {
        if (relPath.isEmpty()) return true
        if (dirs[share]?.contains(relPath) == true) return true
        return files[share]?.keys?.any { it.startsWith("$relPath/") } == true
    }

    private fun renameDir(share: String, from: String, to: String) {
        if (isDir(share, to) || files.getValue(share).containsKey(to)) throw SmbFailure.Other("$to exists")
        val all = files.getValue(share)
        val m = mtimes.getOrPut(share) { mutableMapOf() }
        for (path in all.keys.filter { it.startsWith("$from/") }.toList()) {
            val moved = to + path.removePrefix(from)
            all[moved] = all.remove(path)!!
            m.remove(path)?.let { m[moved] = it }
        }
        val d = dirs.getOrPut(share) { mutableSetOf() }
        for (path in d.filter { it == from || it.startsWith("$from/") }.toList()) {
            d.remove(path)
            d += to + path.removePrefix(from)
        }
        d += to
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
        val childDirs = mutableSetOf<String>()
        val entries = mutableListOf<SmbEntry>()
        for ((path, bytes) in all) {
            if (!path.startsWith(prefix)) continue
            val rest = path.removePrefix(prefix)
            val slash = rest.indexOf('/')
            if (slash < 0) {
                entries += SmbEntry(rest, isDirectory = false, sizeBytes = bytes.size.toLong(), modifiedAtMs = mtime(share, path))
            } else {
                childDirs += rest.substring(0, slash)
            }
        }
        // Folders made by mkdir, which no file path implies.
        for (path in dirs[share].orEmpty()) {
            if (!path.startsWith(prefix)) continue
            val rest = path.removePrefix(prefix)
            if (rest.isNotEmpty() && !rest.contains('/')) childDirs += rest
        }
        entries += childDirs.map { SmbEntry(it, isDirectory = true, sizeBytes = 0, modifiedAtMs = DEFAULT_MTIME) }
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
