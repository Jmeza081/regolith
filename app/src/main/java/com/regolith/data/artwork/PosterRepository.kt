package com.regolith.data.artwork

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.util.Log
import androidx.core.net.toUri
import com.regolith.data.db.FolderDao
import com.regolith.data.db.MediaFileDao
import com.regolith.data.db.ServerDao
import com.regolith.data.db.ShareDao
import com.regolith.data.smb.writeReplacing
import com.regolith.domain.artwork.ArtworkKind
import com.regolith.domain.artwork.CropRect
import com.regolith.domain.artwork.ExistingArtwork
import com.regolith.domain.artwork.FolderPoster
import com.regolith.domain.artwork.FolderPosterOutcome
import com.regolith.domain.artwork.PosterFraming
import com.regolith.domain.artwork.PosterSaveOutcome
import com.regolith.domain.artwork.PosterTarget
import com.regolith.domain.media.LocalSource
import com.regolith.domain.smb.ServerAccess
import com.regolith.domain.smb.SmbEntry
import com.regolith.domain.smb.SmbFailure
import com.regolith.domain.smb.SmbGateway
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Writes the poster.jpg the poster editor makes, beside the film on its
 * share, and makes every screen show it straight away. Also the poster a
 * folder is given from a picture on the phone ([uploadFolderPoster], P19).
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
    @ApplicationContext private val context: Context,
    private val mediaFileDao: MediaFileDao,
    private val folderDao: FolderDao,
    private val shareDao: ShareDao,
    private val serverDao: ServerDao,
    private val access: ServerAccess,
    private val gateway: SmbGateway,
    private val artwork: ArtworkRepository,
    private val folderPosters: FolderPosterWriter,
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
            // Also redraws every tile already showing the old picture.
            artwork.adoptPoster(fileId, bytes)
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

    /**
     * A folder that can be given a poster: the pictures it already has
     * ([FolderPoster.existing]) and what keeping them would rename them to
     * ([FolderPoster.keptNames], worked out against the whole listing).
     */
    data class FolderArtwork(val folderName: String, val existing: List<SmbEntry>, val keptNames: Map<String, String>)

    /**
     * Where [folderId] stands for a poster: one listing of it, so the
     * question about the pictures already there can be asked before anything
     * is sent. Null when there is no share behind the folder to write to
     * (the phone's own videos, the demo library).
     *
     * @throws SmbFailure when the folder cannot be listed.
     */
    suspend fun folderArtwork(folderId: Long): FolderArtwork? {
        val loc = locateFolder(folderId) ?: return null
        val entries = gateway.list(loc.host, loc.creds, loc.share, loc.folderRelPath)
        val existing = FolderPoster.existing(entries)
        return FolderArtwork(loc.folderName, existing, FolderPoster.keptNames(existing.map { it.name }, entries.map { it.name }))
    }

    /**
     * Make the picture at [uri] — a content URI from the photo picker —
     * [folderId]'s poster: written as [FolderPoster.NAME] in the folder, the
     * pictures already there handled as [existing] says, and adopted as the
     * folder's artwork straight away so every tile showing it redraws.
     *
     * The picture is decoded and saved again as a JPEG, not copied: the
     * decoder turns a photo the right way up (a phone stores it sideways
     * with a note to rotate it, which most players ignore), reads the HEIF
     * that phones shoot, and the result is sized to [FolderPoster.MAX_LONG_SIDE].
     */
    suspend fun uploadFolderPoster(folderId: Long, uri: String, existing: ExistingArtwork): FolderPosterOutcome {
        val loc = locateFolder(folderId) ?: return FolderPosterOutcome.FAILED
        val bytes = withContext(Dispatchers.IO) { readPoster(uri) } ?: return FolderPosterOutcome.UNREADABLE
        return try {
            val done = folderPosters.write(loc.host, loc.creds, loc.share, loc.folderRelPath, bytes, existing)
            artwork.adoptFolderPoster(folderId, bytes)
            Log.i(TAG, "poster uploaded to ${loc.folderRelPath.ifEmpty { "/" }}: ${bytes.size / 1024} KB, renamed ${done.renamed}, deleted ${done.deleted}")
            FolderPosterOutcome.SAVED
        } catch (e: SmbFailure.Forbidden) {
            FolderPosterOutcome.READ_ONLY
        } catch (e: SmbFailure.AuthFailed) {
            FolderPosterOutcome.READ_ONLY
        } catch (e: SmbFailure.Unreachable) {
            FolderPosterOutcome.UNREACHABLE
        } catch (e: SmbFailure) {
            Log.w(TAG, "poster for ${loc.folderRelPath} not written: $e")
            FolderPosterOutcome.FAILED
        }
    }

    /** The picked picture as poster JPEG bytes, or null when it cannot be read. Blocking. */
    private fun readPoster(uri: String): ByteArray? = try {
        val source = ImageDecoder.createSource(context.contentResolver, uri.toUri())
        val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val (w, h) = FolderPoster.targetSize(info.size.width, info.size.height)
            decoder.setTargetSize(w, h)
            // compress() cannot read a bitmap that lives on the GPU.
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        try {
            ByteArrayOutputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
                out.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    } catch (e: IOException) {
        Log.w(TAG, "picked picture not readable: $e")
        null
    } catch (e: SecurityException) {
        // The picker's grant ran out, or the picture was taken away meanwhile.
        Log.w(TAG, "picked picture not readable: $e")
        null
    } catch (e: IllegalArgumentException) {
        Log.w(TAG, "picked picture not readable: $e")
        null
    }

    private class FolderLocation(
        val host: com.regolith.domain.smb.SmbHost,
        val creds: com.regolith.domain.smb.SmbCredentials,
        val share: String,
        val folderRelPath: String,
        val folderName: String,
    )

    private suspend fun locateFolder(folderId: Long): FolderLocation? {
        val folder = folderDao.byId(folderId) ?: return null
        val share = shareDao.byId(folder.shareId) ?: return null
        val server = serverDao.byId(share.serverId) ?: return null
        if (LocalSource.isLocal(server.host)) return null
        return FolderLocation(
            host = access.hostFor(server.id),
            creds = access.credentialsFor(server.id),
            share = share.name,
            folderRelPath = folder.relPath,
            folderName = folder.name.ifEmpty { share.name },
        )
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
