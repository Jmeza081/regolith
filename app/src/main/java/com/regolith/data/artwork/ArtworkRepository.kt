package com.regolith.data.artwork

import android.graphics.Bitmap
import android.util.Log
import com.regolith.data.db.ArtworkDao
import com.regolith.data.db.ArtworkEntity
import com.regolith.data.db.FolderDao
import com.regolith.data.db.MediaFileDao
import com.regolith.data.db.ServerDao
import com.regolith.data.db.ShareDao
import com.regolith.data.repository.SourceRepository
import com.regolith.domain.artwork.ArtworkCandidates
import com.regolith.domain.artwork.ArtworkKind
import com.regolith.domain.artwork.ArtworkOwner
import com.regolith.domain.artwork.ArtworkRequest
import com.regolith.domain.artwork.ArtworkSource
import com.regolith.domain.smb.SmbCredentials
import com.regolith.domain.smb.SmbEntry
import com.regolith.domain.smb.SmbFailure
import com.regolith.domain.smb.SmbGateway
import com.regolith.domain.smb.SmbHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Finds, generates and caches artwork (design section 08). One call,
 * [resolve], walks the source order for an owner and writes BOTH kinds
 * (poster and thumb) from whatever it found, so the expensive part, opening
 * a video over SMB, happens once per file rather than once per tile shape.
 *
 * Nothing is ever written to the share.
 *
 * Failure policy: a share that cannot be reached is transient, nothing is
 * recorded and the next request tries again. A file the decoder cannot
 * read gets a PLACEHOLDER row so the grid stops hammering it; placeholders
 * expire after a day in case the user adds a poster.jpg later.
 */
@Singleton
class ArtworkRepository @Inject constructor(
    private val artworkDao: ArtworkDao,
    private val mediaFileDao: MediaFileDao,
    private val folderDao: FolderDao,
    private val shareDao: ShareDao,
    private val serverDao: ServerDao,
    private val sources: SourceRepository,
    private val gateway: SmbGateway,
    private val frames: FrameSourceFactory,
    private val store: ArtworkStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Two extractions at a time: enough to fill a grid, not enough to starve the player. */
    private val extractionSlots = Semaphore(2)

    /** One in-flight resolution per owner; a grid asking for poster and thumb shares it. */
    private val inFlight = mutableMapOf<ArtworkOwner, Deferred<Unit>>()
    private val inFlightLock = Mutex()

    /** Folder listings cached briefly so twenty tiles in one folder cost one SMB list. */
    private val listings = mutableMapOf<Pair<Long, String>, Pair<Long, List<SmbEntry>>>()
    private val listingsLock = Mutex()

    fun observe(request: ArtworkRequest): Flow<ArtworkEntity?> =
        artworkDao.observe(request.owner.typeName, request.owner.id, request.kind.name)

    fun observeCount(): Flow<Int> = artworkDao.observeCount()

    /** The cached row if it is usable now, without touching the share. */
    suspend fun cached(request: ArtworkRequest): ArtworkEntity? {
        val row = artworkDao.get(request.owner.typeName, request.owner.id, request.kind.name) ?: return null
        return when {
            row.source == ArtworkSource.PLACEHOLDER.name -> row.takeIf { System.currentTimeMillis() - it.updatedAtMs < PLACEHOLDER_TTL_MS }
            store.fileFor(row.relPath).exists() -> row
            else -> null // directory cleared or wiped; regenerate
        }
    }

    /**
     * Return usable artwork for [request], resolving it first if needed.
     * Null means "could not, try later" (share unreachable).
     */
    suspend fun resolve(request: ArtworkRequest): ArtworkEntity? {
        cached(request)?.let { return it }
        val job = inFlightLock.withLock {
            inFlight.getOrPut(request.owner) {
                scope.async {
                    try {
                        extractionSlots.withPermit { resolveOwner(request.owner) }
                    } finally {
                        inFlightLock.withLock { inFlight.remove(request.owner) }
                    }
                }
            }
        }
        job.await()
        return cached(request)
    }

    /** Forget everything: the `artwork` table and the directory. Settings › Media. */
    suspend fun clearAll() {
        artworkDao.deleteAll()
        store.clear()
        listingsLock.withLock { listings.clear() }
    }

    fun cacheSizeBytes(): Long = store.sizeBytes()

    /** Where a ready row's bytes are. */
    fun fileFor(row: ArtworkEntity): java.io.File = store.fileFor(row.relPath)

    // --- the pipeline

    private suspend fun resolveOwner(owner: ArtworkOwner) {
        try {
            when (owner) {
                is ArtworkOwner.File -> resolveFile(owner)
                is ArtworkOwner.Folder -> resolveFolder(owner)
            }
        } catch (e: SmbFailure) {
            Log.w(TAG, "artwork for $owner deferred: ${e.message}")
        } catch (e: Exception) {
            Log.w(TAG, "artwork for $owner failed: $e")
            placeholder(owner)
        }
    }

    private suspend fun resolveFile(owner: ArtworkOwner.File) {
        val file = mediaFileDao.byId(owner.id) ?: return
        val location = locate(file.shareId) ?: return
        val folderRelPath = file.relPath.substringBeforeLast('/', "")
        val siblings = listing(location, file.shareId, folderRelPath)

        // 1 + 2: sidecar beside a title, image with the video's basename.
        for (candidate in ArtworkCandidates.forFile(file.name, siblings)) {
            val path = if (folderRelPath.isEmpty()) candidate.name else "$folderRelPath/${candidate.name}"
            val bytes = readImage(location, path) ?: continue
            if (saveEncoded(bytes, owner, candidate.source)) return
        }

        // 3 + 4: open the file once for cover art, then a frame at 10%.
        frames.open(location.host, location.credentials, location.share, file.relPath).use { source ->
            mediaFileDao.fillBasics(file.id, source.durationMs, source.width, source.height)
            source.embeddedPicture()?.let { if (saveEncoded(it, owner, ArtworkSource.EMBEDDED)) return }
            val duration = source.durationMs ?: 0L
            val frame = source.frameAt(ArtworkCandidates.framePositionMs(duration), FRAME_MAX_WIDTH, FRAME_MAX_HEIGHT)
            if (frame != null) {
                try {
                    if (saveBitmap(frame, owner, ArtworkSource.FRAMEGRAB)) return
                } finally {
                    frame.recycle()
                }
            }
        }
        // 5
        placeholder(owner)
    }

    private suspend fun resolveFolder(owner: ArtworkOwner.Folder) {
        val folder = folderDao.byId(owner.id) ?: return
        val location = locate(folder.shareId) ?: return
        val entries = listing(location, folder.shareId, folder.relPath)
        for (candidate in ArtworkCandidates.forFolder(entries)) {
            val path = if (folder.relPath.isEmpty()) candidate.name else "${folder.relPath}/${candidate.name}"
            val bytes = readImage(location, path) ?: continue
            if (saveEncoded(bytes, owner, candidate.source)) return
        }
        // A 2×2 mosaic of its files' frames arrives with collections in Phase 4.
        placeholder(owner)
    }

    private suspend fun saveEncoded(bytes: ByteArray, owner: ArtworkOwner, source: ArtworkSource): Boolean {
        var any = false
        for (kind in ArtworkKind.entries) {
            if (store.saveEncoded(bytes, owner, kind)) {
                record(owner, kind, source)
                any = true
            }
        }
        return any
    }

    private suspend fun saveBitmap(bitmap: Bitmap, owner: ArtworkOwner, source: ArtworkSource): Boolean {
        var any = false
        for (kind in ArtworkKind.entries) {
            if (store.save(bitmap, owner, kind)) {
                record(owner, kind, source)
                any = true
            }
        }
        return any
    }

    private suspend fun record(owner: ArtworkOwner, kind: ArtworkKind, source: ArtworkSource) {
        artworkDao.upsert(
            ArtworkEntity(
                ownerType = owner.typeName,
                ownerId = owner.id,
                kind = kind.name,
                source = source.name,
                relPath = store.relPathFor(owner, kind),
                width = kind.width,
                height = kind.height,
                updatedAtMs = System.currentTimeMillis(),
            ),
        )
    }

    private suspend fun placeholder(owner: ArtworkOwner) {
        store.delete(owner)
        for (kind in ArtworkKind.entries) {
            artworkDao.upsert(
                ArtworkEntity(
                    ownerType = owner.typeName,
                    ownerId = owner.id,
                    kind = kind.name,
                    source = ArtworkSource.PLACEHOLDER.name,
                    relPath = "",
                    width = 0,
                    height = 0,
                    updatedAtMs = System.currentTimeMillis(),
                ),
            )
        }
    }

    // --- share access

    private data class Location(val host: SmbHost, val credentials: SmbCredentials, val share: String)

    private suspend fun locate(shareId: Long): Location? {
        val share = shareDao.byId(shareId) ?: return null
        val server = serverDao.byId(share.serverId) ?: return null
        return Location(SmbHost(server.host, server.port), sources.credentialsFor(server.id), share.name)
    }

    private suspend fun listing(location: Location, shareId: Long, relPath: String): List<SmbEntry> {
        val key = shareId to relPath
        listingsLock.withLock {
            listings[key]?.let { (at, entries) -> if (System.currentTimeMillis() - at < LISTING_TTL_MS) return entries }
        }
        val entries = gateway.list(location.host, location.credentials, location.share, relPath)
        listingsLock.withLock { listings[key] = System.currentTimeMillis() to entries }
        return entries
    }

    /** Whole image file, or null if it is unreadable. Sizes were already capped by the candidate rules. */
    private fun readImage(location: Location, relPath: String): ByteArray? = try {
        gateway.open(location.host, location.credentials, location.share, relPath).use { src ->
            if (src.size > ArtworkCandidates.MAX_IMAGE_BYTES) return null
            val out = ByteArrayOutputStream(src.size.toInt().coerceAtLeast(0))
            val buffer = ByteArray(64 * 1024)
            var position = 0L
            while (true) {
                val n = src.readAt(position, buffer, 0, buffer.size)
                if (n <= 0) break
                out.write(buffer, 0, n)
                position += n
            }
            out.toByteArray()
        }
    } catch (e: SmbFailure.NotFound) {
        null
    }

    private companion object {
        const val TAG = "Regolith/Artwork"
        const val LISTING_TTL_MS = 5 * 60_000L
        const val PLACEHOLDER_TTL_MS = 24 * 3_600_000L
        // Grab at poster height so the 500×750 crop is not upscaled from a 16:9 frame.
        const val FRAME_MAX_WIDTH = 1334
        const val FRAME_MAX_HEIGHT = 750
    }
}
