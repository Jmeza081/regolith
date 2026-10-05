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
    // sidecars. Files are created on a share for two reasons only:
    //  - Regolith's own small files, whole, through [write] — and [write] is
    //    only ever reached through `data/smb/ReplacingWrite.kt`, for a film's
    //    `<basename>.chapters.txt` and a `poster.jpg` from the poster editor.
    //  - Files the user chose on the phone and asked to upload (P16),
    //    streamed through [openForAppend] into a `.part` and renamed once
    //    complete, by the upload queue and nothing else.
    // Keep it to those two: nothing else on a share is Regolith's to create.

    /** Create or replace one file with [bytes]. Returns the file's modified time afterwards. */
    suspend fun write(host: SmbHost, credentials: SmbCredentials, share: String, relPath: String, bytes: ByteArray): Long

    /**
     * Open [relPath] for writing at its END, creating it if it is not there
     * (P16 uploads). Unlike [write] nothing is held in memory: a 4 GB video
     * goes up through a 1 MiB buffer, and a file the network cut short
     * carries on from [SmbWriteSink.startOffset] instead of starting again.
     *
     * Only ever aim it at a `.part` name. What it writes is a file in
     * progress, and a file in progress must never sit under a name somebody
     * could open. Blocking, like [open]; the caller closes it.
     */
    fun openForAppend(host: SmbHost, credentials: SmbCredentials, share: String, relPath: String): SmbWriteSink

    /**
     * Free space on [share] for this login, or null when the server will not
     * say (some refuse guests). Asked before an upload starts, so a share
     * with no room fails in a second rather than after 600 MB.
     */
    suspend fun freeBytes(host: SmbHost, credentials: SmbCredentials, share: String): Long?

    /**
     * Give an uploaded file the modified time it had on the phone. Best
     * effort by contract: a server that refuses leaves the upload time,
     * which is not worth failing an upload over.
     */
    suspend fun setModifiedTime(host: SmbHost, credentials: SmbCredentials, share: String, relPath: String, modifiedAtMs: Long)

    /**
     * Rename within the share. This is also the MOVE primitive: a move is a
     * rename into another folder, which the server does as one metadata
     * operation — measured at 32 MB in 7 ms, no bytes on the wire — so a
     * move cannot half-finish the way a copy-then-delete can.
     *
     * [replace] false is the safe default, and deliberately so: jcifs-ng's
     * replacing rename DESTROYS whatever already has the target name
     * without a word (measured). Only `writeReplacing`
     * (`data/smb/ReplacingWrite.kt`) passes true, to swap a finished `.part`
     * over the real name.
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

    // --- Folders (P13). [rename] already moves a directory — the server
    // does the whole subtree in one metadata operation — but a folder
    // cannot be CREATED or DELETED through the file calls above: both of
    // them address a path without a trailing slash, which is how jcifs is
    // told "this is a file", and neither one matches a directory.

    /**
     * Create one directory. The parent must already exist; this does not
     * make a path, only its last segment.
     *
     * Fails if anything already has that name, which is deliberate — a
     * silent success on an existing folder would let "new folder" quietly
     * mean "the one that is already there".
     */
    suspend fun mkdir(host: SmbHost, credentials: SmbCredentials, share: String, relPath: String)

    /**
     * Delete a directory **and everything inside it**, however deep.
     *
     * Separate from [delete] because the difference is not a detail: this
     * one takes everything beneath, files the app has never listed
     * included, and there is no undo. Only ever call it behind a confirm
     * dialog that says so.
     *
     * [relPath] must name a folder inside the share; the share root itself
     * is rejected rather than emptied.
     */
    suspend fun deleteFolder(host: SmbHost, credentials: SmbCredentials, share: String, relPath: String)
}
