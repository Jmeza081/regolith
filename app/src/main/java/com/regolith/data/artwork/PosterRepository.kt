package com.regolith.data.artwork

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.provider.OpenableColumns
import android.util.Log
import androidx.core.graphics.scale
import androidx.core.net.toUri
import com.regolith.data.db.FolderDao
import com.regolith.data.db.MediaFileDao
import com.regolith.data.db.ServerDao
import com.regolith.data.db.ShareDao
import com.regolith.data.db.ShareFileDao
import com.regolith.data.pictures.PictureOriginals
import com.regolith.data.repository.LibraryRepository
import com.regolith.domain.artwork.AnimatedPoster
import com.regolith.domain.artwork.ArtworkCandidates
import com.regolith.domain.artwork.ArtworkKind
import com.regolith.domain.artwork.CropRect
import com.regolith.domain.artwork.FolderPoster
import com.regolith.domain.artwork.FolderPosterOutcome
import com.regolith.domain.artwork.PickedPoster
import com.regolith.domain.artwork.PosterFraming
import com.regolith.domain.artwork.PosterSaveOutcome
import com.regolith.domain.artwork.PosterTarget
import com.regolith.domain.artwork.WholePoster
import com.regolith.domain.media.LocalSource
import com.regolith.domain.smb.ServerAccess
import com.regolith.domain.smb.SmbCredentials
import com.regolith.domain.smb.SmbFailure
import com.regolith.domain.smb.SmbGateway
import com.regolith.domain.smb.SmbHost
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Every poster the app writes into a folder on a share, and the bookkeeping
 * that makes every screen show it straight away:
 *
 * - a film's frame, cut 2:3 in the poster editor ([save]);
 * - a picture already on the share, cut 2:3 ([posterFromPicture]), copied
 *   whole when players could not take it as it is, or renamed to be the
 *   poster ([adoptPicture]) — P20;
 * - a picture from the phone, cut 2:3 or whole ([posterFromPhone], P19).
 *
 * Whatever acted as the folder's artwork before is kept, renamed with the
 * day ([FolderPoster.keptNames]: `poster (8 Oct).jpg`), never deleted; the
 * screens ask first with [keptFor] / [keptIn]. The share writes are
 * [FolderPosterWriter]'s, so a dropped connection never leaves half a
 * poster or a folder with its old poster gone and no new one.
 *
 * Afterwards the folder's rows follow what happened (a renamed picture keeps
 * its id, so its thumbnail and place stay), the poster is adopted as the
 * folder's artwork so every tile redraws, and the folder is listed again so
 * a new `poster.jpg` shows under Images at once.
 */
@Singleton
class PosterRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val mediaFileDao: MediaFileDao,
    private val folderDao: FolderDao,
    private val shareDao: ShareDao,
    private val serverDao: ServerDao,
    private val shareFileDao: ShareFileDao,
    private val access: ServerAccess,
    private val gateway: SmbGateway,
    private val artwork: ArtworkRepository,
    private val folderPosters: FolderPosterWriter,
    private val originals: PictureOriginals,
    private val library: LibraryRepository,
) {
    /**
     * "Poster set for …" lines for the player, which is where the editor
     * returns to. A buffered channel rather than a shared flow so the line
     * waits for the player to come back on screen instead of being dropped
     * while nobody is listening.
     */
    private val _saved = Channel<String>(Channel.BUFFERED)
    val saved: Flow<String> = _saved.receiveAsFlow()

    /**
     * "Poster set for …" after a poster made from a picture, for the app's
     * own message line: Set as poster closes as it finishes, and the line
     * belongs on whatever is under it by then (the lightbox, an album).
     */
    private val _notices = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val notices: SharedFlow<String> = _notices.asSharedFlow()

    // ── a film's frame (the poster editor) ─────────────────────────────

    /** Where a poster for [fileId] would go, or null when it has nowhere to go. Database only. */
    suspend fun target(fileId: Long): PosterTarget? {
        val loc = locate(fileId) ?: return null
        return PosterTarget(folderName = loc.folderName)
    }

    /** The folder [fileId]'s poster goes in: the folder it is in. Database only. */
    suspend fun folderOf(fileId: Long): Long? = mediaFileDao.byId(fileId)?.folderId

    /**
     * What a poster for [fileId] would keep: the pictures acting as its
     * folder's artwork now, each with the dated name it would be kept as.
     * Empty when there are none, and nothing needs asking.
     *
     * @throws SmbFailure when the folder cannot be listed.
     */
    suspend fun keptFor(fileId: Long): Map<String, String> {
        val loc = locate(fileId) ?: return emptyMap()
        return kept(loc.place, FolderPoster.NAME, leave = null)
    }

    /**
     * Cut [crop] out of [frame], write it as poster.jpg beside [fileId], and
     * adopt it as the folder's artwork. The pictures already acting as its
     * artwork are kept under dated names.
     */
    suspend fun save(fileId: Long, frame: Bitmap, crop: CropRect): PosterSaveOutcome {
        val loc = locate(fileId) ?: return PosterSaveOutcome.FAILED
        val outcome = writing(loc.place) {
            val bytes = withContext(Dispatchers.Default) { encode(frame, crop) }
            val done = folderPosters.write(loc.place.host, loc.place.creds, loc.place.share, loc.place.relPath, bytes, LocalDate.now())
            finish(loc.place, done, bytes)
        }
        if (outcome == FolderPosterOutcome.SAVED) _saved.trySend("Poster set for ${loc.folderName}")
        return when (outcome) {
            FolderPosterOutcome.SAVED, FolderPosterOutcome.SAVED_STILL_NESTED, FolderPosterOutcome.SAVED_STILL_TOO_BIG -> PosterSaveOutcome.SAVED
            FolderPosterOutcome.READ_ONLY -> PosterSaveOutcome.READ_ONLY
            FolderPosterOutcome.UNREACHABLE -> PosterSaveOutcome.UNREACHABLE
            FolderPosterOutcome.UNREADABLE, FolderPosterOutcome.FAILED -> PosterSaveOutcome.FAILED
        }
    }

    // ── a picture on the share (P20) ───────────────────────────────────

    /** A picture on the share as its folder's poster, before anything is decided. */
    data class PicturePoster(
        val pictureId: Long,
        val folderId: Long,
        val folderName: String,
        /** The picture's file name, "IMG_4821.HEIC". */
        val name: String,
        /** How "Use whole picture" would go ([FolderPoster.whole]). */
        val whole: WholePoster,
    )

    /** Where [pictureId] would be the poster, and how it would go whole. Null when it has no share to write to. Database only. */
    suspend fun picturePoster(pictureId: Long): PicturePoster? {
        val row = shareFileDao.byId(pictureId) ?: return null
        val place = placeOf(row.folderId) ?: return null
        val worn = FolderPoster.worn(shareFileDao.inFolder(row.folderId).map { it.name })
        return PicturePoster(pictureId, row.folderId, place.folderName, row.name, FolderPoster.whole(row.name, row.sizeBytes, worn))
    }

    /** The picture at [pictureId], fetched whole and decoded for framing ([decodeFile]); null when it cannot be had. */
    suspend fun decodePicture(pictureId: Long): Bitmap? {
        val file = originals.file(pictureId) ?: return null
        return withContext(Dispatchers.IO) { decodeFile(file) }
    }

    /**
     * What a new poster called [name] in [folderId] would keep (see
     * [keptFor]). [leave] is a picture the poster is made from, which stays
     * as it is ([FolderPoster.keeping]).
     *
     * @throws SmbFailure when the folder cannot be listed.
     */
    suspend fun keptIn(folderId: Long, name: String, leave: String?): Map<String, String> {
        val place = placeOf(folderId) ?: return emptyMap()
        return kept(place, name, leave)
    }

    /**
     * Make poster.jpg for [pictureId]'s folder out of [picture], the picture
     * as decoded for framing: the [crop] of it, or — null — all of it, for a
     * picture players could not take as it is ([WholePoster.COPY]). The
     * picture itself stays as it is.
     */
    suspend fun posterFromPicture(pictureId: Long, picture: Bitmap, crop: CropRect?): FolderPosterOutcome {
        val row = shareFileDao.byId(pictureId) ?: return FolderPosterOutcome.FAILED
        val place = placeOf(row.folderId) ?: return FolderPosterOutcome.FAILED
        return writing(place) {
            val bytes = withContext(Dispatchers.Default) { encodePoster(picture, crop) }
            val done = folderPosters.write(place.host, place.creds, place.share, place.relPath, bytes, LocalDate.now(), leave = row.name)
            finish(place, done, bytes)
        }.also { notify(it, place) }
    }

    /**
     * Make [pictureId] its folder's poster as it is, by renaming it
     * `poster.<its extension>` ([WholePoster.RENAME]). Its row follows the
     * rename, so it keeps its id, its thumbnail and its place.
     */
    suspend fun adoptPicture(pictureId: Long): FolderPosterOutcome {
        val row = shareFileDao.byId(pictureId) ?: return FolderPosterOutcome.FAILED
        val place = placeOf(row.folderId) ?: return FolderPosterOutcome.FAILED
        val file = originals.file(pictureId) ?: return FolderPosterOutcome.UNREADABLE
        return writing(place) {
            val bytes = withContext(Dispatchers.IO) { file.readBytes() }
            val name = FolderPoster.renamedTo(row.name)
            val done = folderPosters.adopt(place.host, place.creds, place.share, place.relPath, row.name, LocalDate.now(), name)
            finish(place, done, bytes, adopted = row.name to name)
        }.also { notify(it, place) }
    }

    // ── a picture from the phone (P19) ─────────────────────────────────

    /** A picture from the phone as [folderId]'s poster, before anything is decided. */
    data class PhonePoster(
        val folderName: String,
        /** What it goes up as, whole: a GIF that moves, or a still. */
        val picked: PickedPoster,
    )

    /** Where the picture at [uri], a content URI from the photo picker, would be [folderId]'s poster. Null when the folder has no share. */
    suspend fun phonePoster(folderId: Long, uri: String): PhonePoster? {
        val place = placeOf(folderId) ?: return null
        return withContext(Dispatchers.IO) { PhonePoster(place.folderName, pickedPoster(uri, place.relPath)) }
    }

    /** The picture at [uri], decoded for framing ([decodeFile]); null when it cannot be read. */
    suspend fun decodePhone(uri: String): Bitmap? = withContext(Dispatchers.IO) {
        decode("picked picture") { ImageDecoder.createSource(context.contentResolver, uri.toUri()) }
    }

    /**
     * Make [folderId]'s poster from the picture at [uri]: the [crop] of
     * [picture] (as decoded for framing), or, with no crop, the picture
     * whole — decoded and saved again as a JPEG, which turns a phone photo
     * the right way up, reads the HEIF phones shoot and sizes it to
     * [FolderPoster.MAX_LONG_SIDE]. A GIF for a top-level folder goes up
     * whole and unchanged as `poster.gif`, and moves on the Library
     * ([AnimatedPoster]).
     */
    suspend fun posterFromPhone(folderId: Long, uri: String, picture: Bitmap?, crop: CropRect?): FolderPosterOutcome {
        val place = placeOf(folderId) ?: return FolderPosterOutcome.FAILED
        val (picked, bytes) = withContext(Dispatchers.IO) {
            if (crop != null && picture != null) {
                PickedPoster.STILL to encodePoster(picture, crop)
            } else {
                readPicked(uri, pickedPoster(uri, place.relPath)) ?: return@withContext null
            }
        } ?: return FolderPosterOutcome.UNREADABLE
        val outcome = writing(place) {
            val done = folderPosters.write(place.host, place.creds, place.share, place.relPath, bytes, LocalDate.now(), picked.fileName)
            finish(place, done, bytes)
        }
        val result = if (outcome != FolderPosterOutcome.SAVED) {
            outcome
        } else {
            when (picked) {
                PickedPoster.STILL_TOO_BIG -> FolderPosterOutcome.SAVED_STILL_TOO_BIG
                PickedPoster.STILL_NESTED -> FolderPosterOutcome.SAVED_STILL_NESTED
                PickedPoster.STILL, PickedPoster.ANIMATED -> FolderPosterOutcome.SAVED
            }
        }
        return result.also { notify(it, place) }
    }

    // ── the work every path shares ─────────────────────────────────────

    /** What was asked about, worked out from one listing of the folder. */
    private suspend fun kept(place: Place, name: String, leave: String?): Map<String, String> =
        FolderPoster.keeping(gateway.list(place.host, place.creds, place.share, place.relPath), name, LocalDate.now(), leave)

    /** [block] against the share, its failures turned into outcomes. */
    private suspend fun writing(place: Place, block: suspend () -> Unit): FolderPosterOutcome = try {
        block()
        FolderPosterOutcome.SAVED
    } catch (e: SmbFailure.Forbidden) {
        FolderPosterOutcome.READ_ONLY
    } catch (e: SmbFailure.AuthFailed) {
        // Signed in fine, refused the write: the same thing to the user.
        FolderPosterOutcome.READ_ONLY
    } catch (e: SmbFailure.Unreachable) {
        FolderPosterOutcome.UNREACHABLE
    } catch (e: SmbFailure) {
        Log.w(TAG, "poster for ${place.relPath.ifEmpty { "/" }} not written: $e")
        FolderPosterOutcome.FAILED
    }

    /**
     * The share has its new poster: the rows follow the renames (kept
     * pictures, and [adopted], the picture renamed to be the poster), the
     * poster's [bytes] are adopted as the folder's artwork, and the folder is
     * listed again so a new file has its row.
     */
    private suspend fun finish(place: Place, done: FolderPosterWriter.Done, bytes: ByteArray, adopted: Pair<String, String>? = null) {
        fun path(name: String) = if (place.relPath.isEmpty()) name else "${place.relPath}/$name"
        for ((from, to) in done.renamed + listOfNotNull(adopted)) {
            shareFileDao.relocate(place.shareId, path(from), path(to), to, place.folderId)
        }
        // Also redraws every tile already showing the old picture.
        artwork.adoptFolderPoster(place.folderId, bytes)
        Log.i(TAG, "poster set in ${place.relPath.ifEmpty { "/" }}: ${bytes.size / 1024} KB, kept ${done.renamed}${adopted?.let { ", renamed ${it.first}" }.orEmpty()}")
        try {
            library.refreshFolder(place.folderId)
        } catch (e: SmbFailure) {
            // The poster is there; its row arrives with the next listing.
            Log.i(TAG, "${place.folderName} not listed again after its poster: $e")
        }
    }

    private fun notify(outcome: FolderPosterOutcome, place: Place) {
        val line = when (outcome) {
            FolderPosterOutcome.SAVED -> "Poster set for ${place.folderName}"
            FolderPosterOutcome.SAVED_STILL_TOO_BIG -> "Poster set for ${place.folderName} as a still: that GIF is over 8 MB"
            FolderPosterOutcome.SAVED_STILL_NESTED -> "Poster set for ${place.folderName} as a still: only top-level folder posters move"
            else -> return
        }
        _notices.tryEmit(line)
    }

    /** What the picture at [uri] goes up as, for the folder at [folderRelPath] ([AnimatedPoster.forPicked]). Blocking. */
    private fun pickedPoster(uri: String, folderRelPath: String): PickedPoster {
        val resolver = context.contentResolver
        val u = uri.toUri()
        val type = runCatching { resolver.getType(u) }.getOrNull()
        val size = runCatching {
            resolver.query(u, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
            }
        }.getOrNull()
        return AnimatedPoster.forPicked(type, size, folderRelPath)
    }

    /**
     * The bytes to send for the picture at [uri], with what they turned out
     * to be: a GIF as it is when [picked] says it can move, otherwise poster
     * JPEG bytes. A GIF that proves bigger than the phone said, or not a GIF
     * at all, goes up as a still after all. Null when it cannot be read. Blocking.
     */
    private fun readPicked(uri: String, picked: PickedPoster): Pair<PickedPoster, ByteArray>? {
        if (picked != PickedPoster.ANIMATED) return readPoster(uri)?.let { picked to it }
        val limit = ArtworkCandidates.MAX_IMAGE_BYTES
        val raw = readUpTo(uri, limit + 1) ?: return null
        return when {
            raw.size > limit -> readPoster(uri)?.let { PickedPoster.STILL_TOO_BIG to it }
            !AnimatedPoster.isGif(raw) -> readPoster(uri)?.let { PickedPoster.STILL to it }
            else -> PickedPoster.ANIMATED to raw
        }
    }

    /** At most [max] bytes of the picture at [uri], as they are, or null when it cannot be read. Blocking. */
    private fun readUpTo(uri: String, max: Long): ByteArray? = try {
        context.contentResolver.openInputStream(uri.toUri())?.use { it.readNBytes(max.toInt()) }
    } catch (e: IOException) {
        Log.w(TAG, "picked picture not readable: $e")
        null
    } catch (e: SecurityException) {
        Log.w(TAG, "picked picture not readable: $e")
        null
    }

    /** The picked picture whole, as poster JPEG bytes ([FolderPoster.targetSize]), or null when it cannot be read. Blocking. */
    private fun readPoster(uri: String): ByteArray? {
        val bitmap = decode("picked picture", sized = FolderPoster::targetSize) {
            ImageDecoder.createSource(context.contentResolver, uri.toUri())
        } ?: return null
        return try {
            jpeg(bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    /** [file], a picture fetched off the share, decoded for framing. Blocking. */
    private fun decodeFile(file: File): Bitmap? = decode(file.name) { ImageDecoder.createSource(file) }

    /**
     * Decode a picture, turned the right way up (EXIF, or a HEIF's `irot`),
     * no bigger than [sized] says — by default [FRAMING_LONG_SIDE] on its
     * long side, enough for a sharp 2:3 crop at [FolderPoster.MAX_LONG_SIDE].
     * Null when it cannot be read. Blocking.
     */
    private fun decode(
        what: String,
        sized: (Int, Int) -> Pair<Int, Int> = { w, h -> framingSize(w, h) },
        source: () -> ImageDecoder.Source,
    ): Bitmap? = try {
        ImageDecoder.decodeBitmap(source()) { decoder, info, _ ->
            val (w, h) = sized(info.size.width, info.size.height)
            decoder.setTargetSize(w, h)
            // compress() and createBitmap() cannot read a bitmap that lives on the GPU.
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    } catch (e: IOException) {
        Log.w(TAG, "$what not readable: $e")
        null
    } catch (e: SecurityException) {
        // The picker's grant ran out, or the picture was taken away meanwhile.
        Log.w(TAG, "$what not readable: $e")
        null
    } catch (e: IllegalArgumentException) {
        Log.w(TAG, "$what not readable: $e")
        null
    }

    /** Where a folder's poster goes. */
    private class Place(
        val folderId: Long,
        val shareId: Long,
        val host: SmbHost,
        val creds: SmbCredentials,
        val share: String,
        /** The folder, `""` for the share's root. */
        val relPath: String,
        val folderName: String,
    )

    private suspend fun placeOf(folderId: Long): Place? {
        val folder = folderDao.byId(folderId) ?: return null
        val share = shareDao.byId(folder.shareId) ?: return null
        val server = serverDao.byId(share.serverId) ?: return null
        // The phone's own videos and the demo library have no share behind
        // them to write to.
        if (LocalSource.isLocal(server.host)) return null
        return Place(
            folderId = folder.id,
            shareId = share.id,
            host = access.hostFor(server.id),
            creds = access.credentialsFor(server.id),
            share = share.name,
            relPath = folder.relPath,
            folderName = folder.name.ifEmpty { share.name },
        )
    }

    /** A film and the folder its poster goes in. */
    private class FilmPlace(val place: Place, val folderName: String)

    private suspend fun locate(fileId: Long): FilmPlace? {
        val file = mediaFileDao.byId(fileId) ?: return null
        val place = placeOf(file.folderId) ?: return null
        return FilmPlace(place, place.folderName)
    }

    companion object {
        private const val TAG = "Regolith/Artwork"

        /** The name the app looks for first ([com.regolith.domain.artwork.ArtworkCandidates.sidecarStems]). */
        val POSTER_NAME = ArtworkKind.POSTER.fileName

        /** Good enough that nobody sees blocks at poster size; a 1000x1500 still is a few hundred KB. */
        const val JPEG_QUALITY = 90

        /**
         * The longest side a picture is decoded at to be framed: half again
         * [FolderPoster.MAX_LONG_SIDE], so a 2:3 crop of a landscape photo
         * still fills a full-size poster. A 50 MP photo at full size would
         * be 200 MB of memory; at this, under 30.
         */
        const val FRAMING_LONG_SIDE = 3000

        /** [width] × [height] brought down to [FRAMING_LONG_SIDE] on its long side, never enlarged. */
        fun framingSize(width: Int, height: Int): Pair<Int, Int> {
            val long = maxOf(width, height)
            if (long <= FRAMING_LONG_SIDE) return width to height
            val f = FRAMING_LONG_SIDE.toFloat() / long
            return maxOf(1, Math.round(width * f)) to maxOf(1, Math.round(height * f))
        }

        /** The crop, scaled down to at most [PosterFraming.MAX_OUTPUT_WIDTH] wide, as JPEG bytes: a film's frame. */
        fun encode(frame: Bitmap, crop: CropRect): ByteArray = cut(frame, crop, PosterFraming.outputSize(crop))

        /**
         * A picture's [crop] — or all of it, null — at most
         * [FolderPoster.MAX_LONG_SIDE] on its long side, as JPEG bytes. A
         * photo is sharper than a film's frame, so it is kept bigger, for a
         * television reading the same share.
         */
        fun encodePoster(picture: Bitmap, crop: CropRect?): ByteArray {
            val area = crop ?: CropRect(0, 0, picture.width, picture.height)
            return cut(picture, area, FolderPoster.targetSize(area.width, area.height))
        }

        /**
         * A small copy of [crop] of [source] (all of it, null), for the sheet
         * that asks before a poster is set: [PREVIEW_HEIGHT] tall at most.
         */
        fun preview(source: Bitmap, crop: CropRect?): Bitmap {
            val area = crop ?: CropRect(0, 0, source.width, source.height)
            val left = area.left.coerceIn(0, source.width - 1)
            val top = area.top.coerceIn(0, source.height - 1)
            val w = area.width.coerceAtMost(source.width - left).coerceAtLeast(1)
            val h = area.height.coerceAtMost(source.height - top).coerceAtLeast(1)
            val f = minOf(1f, PREVIEW_HEIGHT.toFloat() / h)
            val piece = Bitmap.createBitmap(source, left, top, w, h)
            if (f >= 1f) return piece
            return piece.scale(maxOf(1, Math.round(w * f)), maxOf(1, Math.round(h * f))).also {
                if (it !== piece && piece !== source) piece.recycle()
            }
        }

        /** Tall enough for the sheet's 2:3 picture on the densest screen. */
        private const val PREVIEW_HEIGHT = 360

        private fun cut(source: Bitmap, crop: CropRect, size: Pair<Int, Int>): ByteArray {
            val left = crop.left.coerceIn(0, source.width - 1)
            val top = crop.top.coerceIn(0, source.height - 1)
            val piece = Bitmap.createBitmap(source, left, top, crop.width.coerceAtMost(source.width - left), crop.height.coerceAtMost(source.height - top))
            val (w, h) = size
            val sized = if (w == piece.width && h == piece.height) piece else piece.scale(w, h)
            return try {
                jpeg(sized)
            } finally {
                if (sized !== piece) sized.recycle()
                if (piece !== source) piece.recycle()
            }
        }

        private fun jpeg(bitmap: Bitmap): ByteArray = ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            out.toByteArray()
        }
    }
}
