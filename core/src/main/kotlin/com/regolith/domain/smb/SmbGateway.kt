package com.regolith.domain.smb

/**
 * Everything the app asks of an SMB server, and nothing about how.
 * The one implementation lives in `data/smb/JcifsGateway.kt`; tests use
 * `FakeSmbGateway`. Nothing outside `data/smb` may import a jcifs class.
 *
 * All calls are blocking network IO. The suspend functions run on
 * `Dispatchers.IO`; [open] is plain-blocking because ExoPlayer calls it
 * from its own loader thread.
 */
interface SmbGateway {

    /**
     * Authenticate against [host] and list its shares. This doubles as the
     * "connect" step: an [SmbFailure.AuthFailed] here is the sign-in-failed
     * screen, an [SmbFailure.Unreachable] the out-of-reach one.
     */
    suspend fun listShares(host: SmbHost, credentials: SmbCredentials): List<SmbShareInfo>

    /** List one directory. [relPath] is `""` for the share root. */
    suspend fun list(host: SmbHost, credentials: SmbCredentials, share: String, relPath: String): List<SmbEntry>

    /**
     * Open one file for random-access reads. Caller closes it. Throws
     * [SmbFailure.NotFound] when the path is not a file; it never creates one.
     */
    fun open(host: SmbHost, credentials: SmbCredentials, share: String, relPath: String): SeekableByteSource

    // --- Writing (P10). The share was read-only to this app until chapter
    // sidecars; these three exist for them and are called from one place,
    // `SidecarWriter`, which only ever names `<basename>.chapters.txt` and
    // its `.part`. Keep it that way: nothing else on a share is Regolith's.

    /** Create or replace one file with [bytes]. Returns the file's modified time afterwards. */
    suspend fun write(host: SmbHost, credentials: SmbCredentials, share: String, relPath: String, bytes: ByteArray): Long

    /** Rename within the share. Fails with [SmbFailure.Forbidden] or [SmbFailure.Other] if the target exists and the server refuses. */
    suspend fun rename(host: SmbHost, credentials: SmbCredentials, share: String, fromRelPath: String, toRelPath: String)

    /** Delete one file. Deleting a file that is not there is not an error. */
    suspend fun delete(host: SmbHost, credentials: SmbCredentials, share: String, relPath: String)
}
