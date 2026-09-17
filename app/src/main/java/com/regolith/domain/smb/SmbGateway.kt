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

    // --- Writing (P10). The share was read-only to this app until chapter
    // sidecars; these three exist for them and are called from one place,
    // `SidecarWriter`, which only ever names `<basename>.chapters.txt` and
    // its `.part`. Keep it that way: nothing else on a share is Regolith's.

    /** Create or replace one file with [bytes]. Returns the file's modified time afterwards. */
    suspend fun write(host: SmbHost, credentials: SmbCredentials, share: String, relPath: String, bytes: ByteArray): Long

    /**
     * Rename within the share. This is also the MOVE primitive: a move is a
     * rename into another folder, which the server does as one metadata
     * operation — measured at 32 MB in 7 ms, no bytes on the wire — so a
     * move cannot half-finish the way a copy-then-delete can.
     *
     * [replace] false is the safe default, and deliberately so: jcifs-ng's
     * replacing rename DESTROYS whatever already has the target name
     * without a word (measured). Only [com.regolith.data.media.SidecarWriter]
     * passes true, to swap a finished `.part` over the real name.
     *
     * A rename cannot cross shares — the server answers "cannot rename
     * between different trees" — so both paths are inside [share].
     */
    suspend fun rename(
        host: SmbHost,
        credentials: SmbCredentials,
        share: String,
        fromRelPath: String,
        toRelPath: String,
        replace: Boolean = false,
    )

    /** Delete one file. Deleting a file that is not there is not an error. */
    suspend fun delete(host: SmbHost, credentials: SmbCredentials, share: String, relPath: String)
}
