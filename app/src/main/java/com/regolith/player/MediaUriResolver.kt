package com.regolith.player

import android.net.Uri
import com.regolith.data.db.MediaFileDao
import com.regolith.data.db.ServerDao
import com.regolith.data.db.ShareDao
import com.regolith.data.repository.SourceRepository
import com.regolith.domain.smb.SmbCredentials
import com.regolith.domain.smb.SmbHost
import javax.inject.Inject
import javax.inject.Singleton

/** Everything [SmbDataSource] needs to open one file. */
data class ResolvedMedia(
    val host: SmbHost,
    val credentials: SmbCredentials,
    val share: String,
    val relPath: String,
    val sizeBytes: Long,
)

/** What [SmbDataSource] depends on; [MediaUriResolver] is the real one, tests pass a lambda. */
fun interface MediaResolver {
    /** Blocking; null when the URI is not ours or the file is unknown. */
    fun resolve(uri: Uri): ResolvedMedia?
}

/**
 * The player addresses files by an app-private URI, `regolith://file/{id}`,
 * never by an smb:// URL (guardrail G2). That keeps credentials out of
 * URIs, logs and media sessions, and lets Phase 5 hand back a `file://`
 * URI for a downloaded copy without the player noticing.
 */
@Singleton
class MediaUriResolver @Inject constructor(
    private val mediaFileDao: MediaFileDao,
    private val shareDao: ShareDao,
    private val serverDao: ServerDao,
    private val sources: SourceRepository,
) : MediaResolver {
    override fun resolve(uri: Uri): ResolvedMedia? = fileIdOf(uri)?.let { resolveBlocking(it) }

    fun uriFor(fileId: Long): Uri = Uri.parse("$SCHEME://$AUTHORITY/$fileId")

    fun fileIdOf(uri: Uri): Long? =
        if (SCHEME.equals(uri.scheme, ignoreCase = true) && AUTHORITY.equals(uri.host, ignoreCase = true)) uri.lastPathSegment?.toLongOrNull() else null

    /** Blocking; called on ExoPlayer's loader thread. Null if the file is unknown. */
    fun resolveBlocking(fileId: Long): ResolvedMedia? {
        val file = mediaFileDao.byIdBlocking(fileId) ?: return null
        val share = shareDao.byIdBlocking(file.shareId) ?: return null
        val server = serverDao.byIdBlocking(share.serverId) ?: return null
        return ResolvedMedia(
            host = SmbHost(server.host, server.port),
            credentials = sources.credentialsForBlocking(server.id),
            share = share.name,
            relPath = file.relPath,
            sizeBytes = file.sizeBytes,
        )
    }

    companion object {
        const val SCHEME = "regolith"
        const val AUTHORITY = "file"
    }
}
