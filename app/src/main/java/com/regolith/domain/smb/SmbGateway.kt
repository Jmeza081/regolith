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

    /** Open one file for random-access reads. Caller closes it. */
    fun open(host: SmbHost, credentials: SmbCredentials, share: String, relPath: String): SeekableByteSource
}
