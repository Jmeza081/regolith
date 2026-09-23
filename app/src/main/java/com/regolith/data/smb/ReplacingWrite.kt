package com.regolith.data.smb

import com.regolith.domain.smb.SmbCredentials
import com.regolith.domain.smb.SmbFailure
import com.regolith.domain.smb.SmbGateway
import com.regolith.domain.smb.SmbHost

/**
 * Write [bytes] to [relPath], replacing whatever is there, without ever
 * leaving a half-written file under the real name.
 *
 * The bytes go to `<relPath>.part` first and are renamed over the real name
 * once complete, so a dropped connection leaves at worst a stray `.part`,
 * never a truncated poster or chapter file that the next scan would read.
 * Some servers refuse to rename onto a name that exists; then the old file
 * is deleted and the rename tried once more. A [SmbFailure.Forbidden] means
 * a read-only share and is the caller's to show; nothing is retried on it.
 *
 * The only two callers are [com.regolith.data.media.SidecarWriter] (chapter
 * files) and [com.regolith.data.artwork.PosterRepository] (poster.jpg). They
 * are the only files Regolith writes on its own account.
 *
 * Returns the modified time of the file as written.
 */
suspend fun SmbGateway.writeReplacing(host: SmbHost, credentials: SmbCredentials, share: String, relPath: String, bytes: ByteArray): Long {
    val part = relPath + PART_SUFFIX
    val mtime = write(host, credentials, share, part, bytes)
    try {
        rename(host, credentials, share, part, relPath, replace = true)
    } catch (e: SmbFailure.Other) {
        delete(host, credentials, share, relPath)
        rename(host, credentials, share, part, relPath, replace = true)
    } catch (e: SmbFailure) {
        // Leave nothing behind under the temporary name either.
        runCatching { delete(host, credentials, share, part) }
        throw e
    }
    return mtime
}

/** What [writeReplacing] appends to the name while the bytes are on their way. */
const val PART_SUFFIX = ".part"
