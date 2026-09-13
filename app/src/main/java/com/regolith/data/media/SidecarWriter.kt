package com.regolith.data.media

import com.regolith.domain.media.ChapterSidecar
import com.regolith.domain.smb.SmbCredentials
import com.regolith.domain.smb.SmbFailure
import com.regolith.domain.smb.SmbGateway
import com.regolith.domain.smb.SmbHost
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The only thing in the app that writes to a share (P10), and it writes
 * exactly one kind of file: `<basename>.chapters.txt` beside a video.
 *
 * A half-written file must never be read as the film's chapters, so the
 * text goes to `<basename>.chapters.txt.part` first and is renamed over
 * the real name once complete. Some servers refuse to rename onto a name
 * that exists; then the old file is deleted and the rename tried once
 * more. A [SmbFailure.Forbidden] means a read-only share and is the
 * caller's to show; nothing is retried on it here.
 */
@Singleton
class SidecarWriter @Inject constructor(
    private val gateway: SmbGateway,
) {
    /** Write the sidecar for [videoName] in [folderRelPath]. Returns the file's modified time on the share. */
    suspend fun write(host: SmbHost, credentials: SmbCredentials, share: String, folderRelPath: String, videoName: String, text: String): Long {
        val real = pathFor(folderRelPath, videoName, ChapterSidecar.SUFFIX)
        val part = pathFor(folderRelPath, videoName, ChapterSidecar.PART_SUFFIX)
        val mtime = gateway.write(host, credentials, share, part, text.toByteArray(Charsets.UTF_8))
        try {
            gateway.rename(host, credentials, share, part, real)
        } catch (e: SmbFailure.Other) {
            gateway.delete(host, credentials, share, real)
            gateway.rename(host, credentials, share, part, real)
        } catch (e: SmbFailure) {
            // Leave nothing behind under the temporary name either.
            runCatching { gateway.delete(host, credentials, share, part) }
            throw e
        }
        return mtime
    }

    /** Remove the sidecar for [videoName]; a missing file is fine. */
    suspend fun delete(host: SmbHost, credentials: SmbCredentials, share: String, folderRelPath: String, videoName: String) {
        gateway.delete(host, credentials, share, pathFor(folderRelPath, videoName, ChapterSidecar.SUFFIX))
    }

    /** Read a sidecar's text, capped at [ChapterSidecar.MAX_BYTES]; null when it is larger than a chapter file can be. */
    suspend fun read(host: SmbHost, credentials: SmbCredentials, share: String, relPath: String): String? {
        gateway.open(host, credentials, share, relPath).use { src ->
            if (src.size > ChapterSidecar.MAX_BYTES) return null
            val out = ByteArray(src.size.toInt())
            var done = 0
            while (done < out.size) {
                val n = src.readAt(done.toLong(), out, done, out.size - done)
                if (n <= 0) break
                done += n
            }
            return String(out, 0, done, Charsets.UTF_8)
        }
    }

    companion object {
        /** `Films/Heat.1995.chapters.txt` for `Films` + `Heat.1995.mkv`; the share root has no folder prefix. */
        fun pathFor(folderRelPath: String, videoName: String, suffix: String): String {
            val base = videoName.substringBeforeLast('.')
            return if (folderRelPath.isEmpty()) base + suffix else "$folderRelPath/$base$suffix"
        }
    }
}
