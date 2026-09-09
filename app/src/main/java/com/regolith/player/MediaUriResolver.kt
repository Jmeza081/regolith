package com.regolith.player

import android.net.Uri
import com.regolith.data.db.MediaFileDao
import com.regolith.data.db.ServerDao
import com.regolith.data.db.ShareDao
import com.regolith.data.repository.SourceRepository
import com.regolith.data.transfer.TransferRepository
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
    private val transfers: TransferRepository,
) : MediaResolver {
    override fun resolve(uri: Uri): ResolvedMedia? = fileIdOf(uri)?.let { resolveBlocking(it) }

    /** The app URI the player and the probe address a file by; the data source resolves it to the share. */
    fun uriFor(fileId: Long): Uri = Uri.parse("$SCHEME://$AUTHORITY/$fileId")

    /**
     * What to hand the player: the finished copy on this device as a
     * `file://` URI when there is one (DefaultDataSource reads it
     * directly), else the app URI. This is the whole of "plays with no
     * network"; the player never knows which it got.
     */
    suspend fun playableUriFor(fileId: Long): Uri = transfers.localFile(fileId)?.let { Uri.fromFile(it) } ?: uriFor(fileId)

    /** True when [uri] is a copy on this device rather than the share. */
    fun isLocal(uri: Uri): Boolean = uri.scheme == "file"

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
