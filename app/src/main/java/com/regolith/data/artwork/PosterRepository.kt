package com.regolith.data.artwork

import android.graphics.Bitmap
import android.util.Log
import coil3.ImageLoader
import com.regolith.data.db.MediaFileDao
import com.regolith.data.db.ServerDao
import com.regolith.data.db.ShareDao
import com.regolith.data.smb.writeReplacing
import com.regolith.domain.artwork.ArtworkKind
import com.regolith.domain.artwork.CropRect
import com.regolith.domain.artwork.PosterFraming
import com.regolith.domain.artwork.PosterSaveOutcome
import com.regolith.domain.artwork.PosterTarget
import com.regolith.domain.media.LocalSource
import com.regolith.domain.smb.ServerAccess
import com.regolith.domain.smb.SmbFailure
import com.regolith.domain.smb.SmbGateway
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Writes the poster.jpg the poster editor makes, beside the film on its
 * share, and makes every screen show it straight away.
 *
 * This is the second file Regolith will ever write on a share (chapter
 * files were the first), and it only happens because the user pressed
 * Save. The write goes through [writeReplacing], so a dropped connection
 * never leaves a half-written poster.jpg for the next scan to read.
 *
 * Replacing is asked for, not assumed: [save] answers
 * [PosterSaveOutcome.EXISTS] until it is called with `replace = true`,
 * which the editor only does after its confirm dialog.
 */
@Singleton
class PosterRepository @Inject constructor(
    private val mediaFileDao: MediaFileDao,
    private val shareDao: ShareDao,
    private val serverDao: ServerDao,
    private val access: ServerAccess,
    private val gateway: SmbGateway,
    private val artwork: ArtworkRepository,
    private val imageLoader: ImageLoader,
) {
    /**
     * "Poster saved to …" lines for the player, which is where the editor
     * returns to. A buffered channel rather than a shared flow so the line
     * waits for the player to come back on screen instead of being dropped
     * while nobody is listening.
     */
    private val _saved = Channel<String>(Channel.BUFFERED)
    val saved: Flow<String> = _saved.receiveAsFlow()

    /** Where a poster for [fileId] would go, or null when it has nowhere to go. Database only. */
    suspend fun target(fileId: Long): PosterTarget? {
        val loc = locate(fileId) ?: return null
        val others = mediaFileDao.inFolder(loc.file.folderId).count { it.id != fileId }
        return PosterTarget(folderName = loc.folderName, sharedWithFolder = others > 0)
    }

    /**
     * Cut [crop] out of [frame], write it as poster.jpg beside [fileId], and
     * adopt it as the artwork for the folder (and the film, when it is alone
     * there).
     *
     * Checks for an existing poster.jpg by LISTING the folder: opening a
     * path that is not there can create an empty file on some servers, which
     * is the last thing this should leave behind.
     */
    suspend fun save(fileId: Long, frame: Bitmap, crop: CropRect, replace: Boolean): PosterSaveOutcome {
        val loc = locate(fileId) ?: return PosterSaveOutcome.FAILED
        return try {
            if (!replace) {
                val names = gateway.list(loc.host, loc.creds, loc.share, loc.folderRelPath).map { it.name }
                if (names.any { it.equals(POSTER_NAME, ignoreCase = true) }) return PosterSaveOutcome.EXISTS
            }
            val bytes = withContext(Dispatchers.Default) { encode(frame, crop) }
            val path = if (loc.folderRelPath.isEmpty()) POSTER_NAME else "${loc.folderRelPath}/$POSTER_NAME"
            gateway.writeReplacing(loc.host, loc.creds, loc.share, path, bytes)
            val owners = artwork.adoptPoster(fileId, bytes)
            // Coil keeps decoded pictures in memory under keys that never
            // change for an owner, so without this a tile already seen this
            // session would keep drawing the old poster from memory.
            imageLoader.memoryCache?.let { cache ->
                val prefixes = owners.map(ArtworkKeyer::ownerPrefix)
                cache.keys.filter { key -> prefixes.any { key.key.startsWith(it) } }.forEach(cache::remove)
            }
            _saved.trySend("Poster saved to ${loc.folderName}")
            PosterSaveOutcome.SAVED
        } catch (e: SmbFailure.Forbidden) {
            PosterSaveOutcome.READ_ONLY
        } catch (e: SmbFailure.AuthFailed) {
            // Signed in fine, refused the write: the same thing to the user.
            PosterSaveOutcome.READ_ONLY
        } catch (e: SmbFailure.Unreachable) {
            PosterSaveOutcome.UNREACHABLE
        } catch (e: SmbFailure) {
            Log.w(TAG, "poster for ${loc.file.relPath} not written: $e")
            PosterSaveOutcome.FAILED
        }
    }

    private class Location(
        val file: com.regolith.data.db.MediaFileEntity,
        val host: com.regolith.domain.smb.SmbHost,
        val creds: com.regolith.domain.smb.SmbCredentials,
        val share: String,
        val folderRelPath: String,
        val folderName: String,
    )

    private suspend fun locate(fileId: Long): Location? {
        val file = mediaFileDao.byId(fileId) ?: return null
        val share = shareDao.byId(file.shareId) ?: return null
        val server = serverDao.byId(share.serverId) ?: return null
        // The phone's own videos and the demo library have no share behind
        // them to write to.
        if (LocalSource.isLocal(server.host)) return null
        val folder = file.relPath.substringBeforeLast('/', "")
        return Location(
            file = file,
            host = access.hostFor(server.id),
            creds = access.credentialsFor(server.id),
            share = share.name,
            folderRelPath = folder,
            folderName = folder.substringAfterLast('/').ifEmpty { share.name },
        )
    }

    companion object {
        private const val TAG = "Regolith/Artwork"

        /** The name the app looks for first ([com.regolith.domain.artwork.ArtworkCandidates.sidecarStems]). */
        val POSTER_NAME = ArtworkKind.POSTER.fileName

        /** Good enough that nobody sees blocks at poster size; a 1000x1500 still is a few hundred KB. */
        const val JPEG_QUALITY = 90

        /** The crop, scaled down to at most [PosterFraming.MAX_OUTPUT_WIDTH] wide, as JPEG bytes. */
        fun encode(frame: Bitmap, crop: CropRect): ByteArray {
            val cut = Bitmap.createBitmap(frame, crop.left, crop.top, crop.width.coerceAtMost(frame.width - crop.left), crop.height.coerceAtMost(frame.height - crop.top))
            val (w, h) = PosterFraming.outputSize(crop)
            val sized = if (w == cut.width && h == cut.height) cut else Bitmap.createScaledBitmap(cut, w, h, true)
            return try {
                ByteArrayOutputStream().use { out ->
                    sized.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
                    out.toByteArray()
                }
            } finally {
                if (sized !== cut) sized.recycle()
                if (cut !== frame) cut.recycle()
            }
        }
    }
}
