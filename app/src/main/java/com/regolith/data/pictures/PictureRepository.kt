package com.regolith.data.pictures

import android.util.Log
import com.regolith.data.db.ServerDao
import com.regolith.data.db.ShareDao
import com.regolith.data.db.ShareFileDao
import com.regolith.data.db.ShareFileEntity
import com.regolith.data.repository.SourceRepository
import com.regolith.domain.media.LocalSource
import com.regolith.domain.media.MediaFileTypes
import com.regolith.domain.media.PictureFacts
import com.regolith.domain.media.PictureHeaders
import com.regolith.domain.smb.BufferedByteSource
import com.regolith.domain.smb.SeekableByteSource
import com.regolith.domain.smb.SmbCredentials
import com.regolith.domain.smb.SmbFailure
import com.regolith.domain.smb.SmbGateway
import com.regolith.domain.smb.SmbHost
import com.regolith.domain.smb.isAboutTheServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The pictures on the share as library items (schema v17): reads each one's
 * header ([PictureHeaders]) once it has been listed, so its shape, the date
 * it was taken and its camera are in Room before anything draws it.
 *
 * Why its own pass, and not part of the listing: a listing is one SMB call
 * however many files a folder holds, and Browse waits on it. Reading a header
 * is one more open per picture — a few milliseconds each on a home network,
 * but a minute for a folder of a thousand over a VPN. So a listing only
 * notes that a picture is new or changed (`measuredAtMs` cleared), and this
 * catches up: for one album as soon as it is opened ([measureFolder]), and
 * for a whole share in the background walk after a scan ([measureShare]).
 *
 * Web analogy: lazily filling `width`/`height` attributes in the database,
 * so the page that renders the images never has to wait for them to load.
 */
@Singleton
class PictureRepository @Inject constructor(
    private val gateway: SmbGateway,
    private val sources: SourceRepository,
    private val shareDao: ShareDao,
    private val serverDao: ServerDao,
    private val shareFileDao: ShareFileDao,
) {
    /** One folder measured at a time, so two screens opening the same album do not read it twice. */
    private val folderLock = Mutex()

    /**
     * Read the header of every picture in [folderId] that has not been read
     * yet. A few at a time: the opens are what take the time, and they wait
     * on the network rather than the phone. Quietly does nothing when the
     * share cannot be reached; the album draws its pictures square until a
     * later look gets through.
     */
    suspend fun measureFolder(folderId: Long) = folderLock.withLock {
        val pending = shareFileDao.unmeasuredInFolder(folderId).filter { MediaFileTypes.isPicture(it.name) }
        if (pending.isEmpty()) return@withLock
        val shareId = pending.first().shareId
        val location = locate(shareId) ?: return@withLock
        try {
            measureAll(location, pending)
        } catch (e: SmbFailure) {
            Log.i(TAG, "measuring folder $folderId stopped: ${e.message}")
        }
    }

    /**
     * Every picture on [shareId] not measured yet, for the background walk.
     * False when the share stopped answering, so the walk can come back later.
     */
    suspend fun measureShare(shareId: Long): Boolean {
        val pending = shareFileDao.unmeasuredInShare(shareId).filter { MediaFileTypes.isPicture(it.name) }
        if (pending.isEmpty()) return true
        val location = locate(shareId) ?: return true
        return try {
            measureAll(location, pending)
            Log.i(TAG, "share $shareId: ${pending.size} picture(s) measured")
            true
        } catch (e: SmbFailure) {
            Log.i(TAG, "measuring share $shareId stopped after a failure: ${e.message}")
            false
        }
    }

    /**
     * What [bytes], a whole picture already read for its thumbnail, says
     * about itself, written down if the row has nothing yet. Saves a second
     * open of the same file by [measureFolder].
     */
    suspend fun noteFrom(row: ShareFileEntity, bytes: ByteArray) {
        if (row.measuredAtMs != null) return
        save(row, PictureHeaders.read(ByteArraySource(bytes)))
    }

    private suspend fun measureAll(location: Location, pictures: List<ShareFileEntity>) = coroutineScope {
        val slots = Semaphore(PARALLEL_OPENS)
        pictures.map { row ->
            async {
                slots.withPermit {
                    val facts = try {
                        withContext(Dispatchers.IO) { read(location, row) }
                    } catch (e: SmbFailure) {
                        // The SERVER going away ends the pass; one picture the
                        // server will not hand over is only that picture.
                        if (e.isAboutTheServer) throw e
                        null
                    }
                    save(row, facts)
                }
            }
        }.awaitAll()
    }

    /**
     * One picture's header, read through a small block cache: a JPEG's EXIF
     * and frame header are in its first 64 KB, and a HEIC's `meta` box too,
     * with its EXIF one more block along at most.
     */
    private fun read(location: Location, row: ShareFileEntity): PictureFacts? =
        gateway.open(location.host, location.credentials, location.share, row.relPath).use { raw ->
            BufferedByteSource(raw, blockSize = HEADER_BLOCK, blockCount = 2).let { PictureHeaders.read(it) }
        }

    private suspend fun save(row: ShareFileEntity, facts: PictureFacts?) {
        shareFileDao.saveFacts(
            id = row.id,
            sizeBytes = row.sizeBytes,
            modifiedAtMs = row.modifiedAtMs,
            width = facts?.width,
            height = facts?.height,
            takenAtMs = facts?.takenAt?.let { at ->
                // The camera's wall clock, in its own zone when it wrote one, or
                // else this phone's: what a gallery app assumes too.
                (facts.takenOffset?.let { at.toInstant(it) } ?: at.atZone(ZoneId.systemDefault()).toInstant()).toEpochMilli()
            },
            camera = facts?.camera,
            measuredAtMs = System.currentTimeMillis(),
        )
    }

    private data class Location(val host: SmbHost, val credentials: SmbCredentials, val share: String)

    /** Where [shareId] is, or null for a share with no server behind it (the demo, the phone). */
    private suspend fun locate(shareId: Long): Location? {
        val share = shareDao.byId(shareId) ?: return null
        val server = serverDao.byId(share.serverId) ?: return null
        if (LocalSource.isLocal(server.host)) return null
        return Location(sources.hostFor(server.id), sources.credentialsFor(server.id), share.name)
    }

    /** A whole picture in memory, read as a source. */
    private class ByteArraySource(private val bytes: ByteArray) : SeekableByteSource {
        override val size: Long get() = bytes.size.toLong()

        override fun readAt(offset: Long, dst: ByteArray, dstOffset: Int, length: Int): Int {
            if (offset >= bytes.size) return -1
            val n = minOf(length.toLong(), bytes.size - offset).toInt()
            System.arraycopy(bytes, offset.toInt(), dst, dstOffset, n)
            return n
        }

        override fun close() = Unit
    }

    private companion object {
        const val TAG = "Regolith/Pictures"

        /** How many pictures' headers are read at once. */
        const val PARALLEL_OPENS = 4

        /** One read covers a header: 64 KB, not the 1 MB a video's read-ahead uses. */
        const val HEADER_BLOCK = 64 * 1024
    }
}
