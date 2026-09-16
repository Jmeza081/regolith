package com.regolith.data.artwork

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.core.graphics.createBitmap
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
import com.regolith.data.db.MediaFileEntity
import com.regolith.domain.smb.SmbCredentials
import com.regolith.domain.smb.SmbEntry
import com.regolith.domain.smb.SmbFailure
import com.regolith.domain.smb.SmbGateway
import com.regolith.domain.smb.SmbHost
import kotlin.math.abs
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
    private val local: com.regolith.player.LocalMedia,
    private val durations: DurationProbe,
    private val grabber: FrameGrabber,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Two extractions at a time: enough to fill a grid, not enough to starve the player. */
    private val extractionSlots = Semaphore(2)

    /**
     * One of those two, at most, may belong to the background walk. The
     * prefetch and the screen draw from the same pool, and a walk of two
     * thousand files would otherwise hold both slots for an hour and make
     * the app feel slower than having no cache at all.
     */
    private val prefetchSlots = Semaphore(1)

    /**
     * One in-flight resolution per (owner, is-backdrop); a grid asking for a
     * poster and a thumb of the same title shares one, and a backdrop request
     * arriving at the same time does not wait behind it. The background walk
     * joins the stills entry, so a tile scrolling into view waits for the
     * work already running rather than starting it again.
     */
    private val inFlight = mutableMapOf<Pair<ArtworkOwner, Boolean>, Deferred<Boolean>>()
    private val inFlightLock = Mutex()

    @Volatile private var generationChecked = false
    private val generationLock = Mutex()

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
        ensureGeneration()
        cached(request)?.let { return it }
        // A backdrop is four times a thumb's bytes and only Title Detail wants
        // one, so it is never written alongside the stills — it has its own
        // key, its own in-flight entry and its own trip through the source
        // order, run for the one title you opened.
        val kinds = if (request.kind == ArtworkKind.BACKDROP) BACKDROP_ONLY else ArtworkKind.stills
        val key = request.owner to (request.kind == ArtworkKind.BACKDROP)
        joinOrStart(key) { extractionSlots.withPermit { resolveOwner(request.owner, kinds) } }
        return cached(request)
    }

    /**
     * Fill the cache for one owner before anything asks for it — the
     * background walk ([com.regolith.data.artwork.ArtworkWorker]).
     *
     * Every kind comes from ONE extraction, which is the whole reason this
     * is its own entry point rather than a loop over [resolve]. On demand,
     * the stills are grabbed when a tile appears and the backdrop is grabbed
     * AGAIN when the title is opened: two trips over the share for one
     * picture. The grab is already 1334x750, comfortably bigger than a
     * 1280x720 backdrop, so writing all three costs nothing extra.
     *
     * Returns false when the share could not be reached, so a walk can stop
     * rather than grind through a thousand files that will all fail.
     */
    suspend fun prefetch(owner: ArtworkOwner): Boolean {
        ensureGeneration()
        if (ArtworkKind.entries.all { cached(ArtworkRequest(owner, it)) != null }) return true
        return joinOrStart(owner to false) {
            prefetchSlots.withPermit { extractionSlots.withPermit { resolveOwner(owner, ArtworkKind.entries) } }
        }
    }

    /** Join the resolution already running for [key], or start it. */
    private suspend fun joinOrStart(key: Pair<ArtworkOwner, Boolean>, body: suspend () -> Boolean): Boolean {
        val job = inFlightLock.withLock {
            inFlight.getOrPut(key) {
                scope.async {
                    try {
                        body()
                    } finally {
                        inFlightLock.withLock { inFlight.remove(key) }
                    }
                }
            }
        }
        return job.await()
    }

    /**
     * Throw away everything the app generated itself when the rules that
     * made it have changed since (see [ArtworkStore.GENERATION]). Images
     * found on the share — a poster.jpg, embedded cover art — are not
     * affected, because nothing about them has changed.
     *
     * Runs at most once per process, before the first resolution.
     */
    private suspend fun ensureGeneration() {
        if (generationChecked) return
        generationLock.withLock {
            if (generationChecked) return
            generationChecked = true
            if (store.generation() >= ArtworkStore.GENERATION) return
            val stale = artworkDao.generated()
            Log.i(TAG, "artwork generation ${store.generation()} -> ${ArtworkStore.GENERATION}: dropping ${stale.size} generated rows")
            for (row in stale) if (row.relPath.isNotEmpty()) store.fileFor(row.relPath).delete()
            artworkDao.deleteGenerated()
            store.setGeneration(ArtworkStore.GENERATION)
        }
    }

    /**
     * A folder's stitched tile is a stand-in for a picture it did not have.
     * When a scan finds that one has since been added, the mosaic row goes
     * so the next request runs the source order again and lands on the real
     * image (design section 08: a sidecar always wins).
     */
    suspend fun onFolderListed(folderId: Long, entries: List<SmbEntry>) {
        if (ArtworkCandidates.forFolder(entries).isEmpty()) return
        artworkDao.deleteMosaic(ArtworkOwner.Folder(folderId).typeName, folderId)
    }

    /** Forget everything: the `artwork` table and the directory. Settings › Media. */
    suspend fun clearAll() {
        artworkDao.deleteAll()
        store.clear()
        // The marker lives inside the directory that was just deleted. Without
        // this, the next launch reads generation 0, decides the cache is stale
        // and throws away everything the clear made you regenerate.
        store.setGeneration(ArtworkStore.GENERATION)
        generationChecked = true
        listingsLock.withLock { listings.clear() }
    }

    fun cacheSizeBytes(): Long = store.sizeBytes()

    /** Where a ready row's bytes are. */
    fun fileFor(row: ArtworkEntity): java.io.File = store.fileFor(row.relPath)

    // --- the pipeline

    /** False only when the share could not be reached; anything else is dealt with here. */
    private suspend fun resolveOwner(owner: ArtworkOwner, kinds: List<ArtworkKind>): Boolean {
        return try {
            when (owner) {
                is ArtworkOwner.File -> resolveFile(owner, kinds)
                is ArtworkOwner.Folder -> resolveFolder(owner, kinds)
            }
            true
        } catch (e: SmbFailure) {
            Log.w(TAG, "artwork for $owner deferred: ${e.message}")
            false
        } catch (e: Exception) {
            Log.w(TAG, "artwork for $owner failed: $e")
            placeholder(owner, kinds)
            true
        }
    }

    private suspend fun resolveFile(owner: ArtworkOwner.File, kinds: List<ArtworkKind>) {
        val file = mediaFileDao.byId(owner.id) ?: return
        // A copy on this device (a download, or the demo library) is opened
        // directly: no share to reach, so this is also the only artwork path
        // that works with the network off.
        local.file(owner.id)?.let { copy ->
            frames.openLocal(copy).use { source ->
                if (grabFrame(file, owner, source, kinds)) return
            }
            placeholder(owner, kinds)
            return
        }
        val location = locate(file.shareId) ?: return
        val folderRelPath = file.relPath.substringBeforeLast('/', "")
        val siblings = listing(location, file.shareId, folderRelPath)

        // 1 + 2: sidecar beside a title, image with the video's basename.
        for (candidate in ArtworkCandidates.forFile(file.name, siblings)) {
            val path = if (folderRelPath.isEmpty()) candidate.name else "$folderRelPath/${candidate.name}"
            val bytes = readImage(location, path) ?: continue
            if (saveEncoded(bytes, owner, candidate.source, kinds)) return
        }

        // 3 + 4: open the file once for cover art, then a frame at the midpoint.
        frames.open(location.host, location.credentials, location.share, file.relPath).use { source ->
            if (grabFrame(file, owner, source, kinds)) return
        }
        // 5
        placeholder(owner, kinds)
    }

    /**
     * Steps 3 and 4 on an already-open file: embedded cover art, else a
     * frame from the midpoint. True when something was written.
     *
     * The log line here is the one to read when a tile looks wrong — it
     * says where the frame was taken from and how the runtime behind that
     * decision was arrived at (`adb logcat -s Regolith/Artwork`).
     */
    private suspend fun grabFrame(file: MediaFileEntity, owner: ArtworkOwner.File, source: FrameSource, kinds: List<ArtworkKind>): Boolean {
        mediaFileDao.fillBasics(file.id, source.durationMs, source.width, source.height, source.rotationDegrees)
        source.embeddedPicture()?.let {
            if (saveEncoded(it, owner, ArtworkSource.EMBEDDED, kinds)) {
                Log.i(TAG, "${file.name}: cover art embedded in the container (no frame grabbed)")
                return true
            }
        }
        val duration = runtimeOf(file, source)
        val at = ArtworkCandidates.framePositionMs(duration)

        // Media3 first: the same extractors and the same data source the
        // player uses, and it says which frame it actually gave you. The
        // platform retriever cannot, and returns the OPENING frame when it
        // fails to seek — the one failure this whole path exists to avoid.
        grabber.frameAt(file.id, at, FRAME_MAX_WIDTH, FRAME_MAX_HEIGHT)?.let { grabbed ->
            val off = grabbed.presentationTimeMs - at
            if (duration > 0 && abs(off) > SEEK_TOLERANCE_MS) {
                // Not fatal — a file whose key frames really are minutes apart
                // is legitimately far off — but it is the line to read when a
                // tile looks like a title card.
                Log.w(TAG, "${file.name}: asked for ${at}ms, got ${grabbed.presentationTimeMs}ms (${off}ms off)")
            } else {
                Log.i(TAG, "${file.name}: frame at ${grabbed.presentationTimeMs}ms of ${duration}ms (media3)")
            }
            try {
                if (saveBitmap(grabbed.bitmap, owner, ArtworkSource.FRAMEGRAB, kinds)) return true
            } finally {
                grabbed.bitmap.recycle()
            }
        }

        // Fallback: the platform retriever on the file we already have open.
        val frame = source.frameAt(at, FRAME_MAX_WIDTH, FRAME_MAX_HEIGHT) ?: return false
        Log.i(TAG, "${file.name}: frame at ${at}ms of ${duration}ms (retriever)")
        return try {
            saveBitmap(frame, owner, ArtworkSource.FRAMEGRAB, kinds)
        } finally {
            frame.recycle()
        }
    }

    /**
     * How long the file is, and therefore where the midpoint is. Three
     * answers, cheapest first — and the third one is the whole point:
     *
     *  1. what the open [FrameSource] says (free, it is already open);
     *  2. what a previous probe wrote to the row (free);
     *  3. Media3's extractors, the same ones the player uses (one more
     *     open of the file).
     *
     * Without step 3 a container the platform retriever cannot time gets
     * `duration = 0`, and 0 means a frame from the first second — a black
     * title card or a studio ident, which is exactly the tile the midpoint
     * grab exists to avoid. The step costs an extra open, but only for the
     * files that would otherwise be wrong.
     *
     * The result is written back to the row, so a rescan or a second kind
     * never pays for it twice.
     */
    private suspend fun runtimeOf(file: MediaFileEntity, source: FrameSource): Long {
        source.durationMs?.let { return it }
        file.durationMs?.let { known ->
            Log.d(TAG, "no runtime from the container for ${file.name}; using the ${known}ms already on the row")
            return known
        }
        val probed = durations.durationMs(file.id)
        if (probed == null || probed <= 0) {
            Log.w(TAG, "no runtime for ${file.name} from the container, the row or the probe: grabbing at 0")
            return 0L
        }
        Log.i(TAG, "no runtime from the container for ${file.name}; the probe says ${probed}ms")
        mediaFileDao.fillBasics(file.id, probed, null, null, null)
        return probed
    }

    private suspend fun resolveFolder(owner: ArtworkOwner.Folder, kinds: List<ArtworkKind>) {
        val folder = folderDao.byId(owner.id) ?: return
        // A sidecar always wins, but it lives on the share, and the mosaic
        // below may not need the share at all — the demo library and anything
        // downloaded have local copies. So an unreachable share is remembered
        // rather than thrown here, and only re-thrown if the mosaic also
        // could not be made: "try again later" must not become a placeholder.
        var unreachable: SmbFailure? = null
        try {
            locate(folder.shareId)?.let { location ->
                for (candidate in ArtworkCandidates.forFolder(listing(location, folder.shareId, folder.relPath))) {
                    val path = if (folder.relPath.isEmpty()) candidate.name else "${folder.relPath}/${candidate.name}"
                    val bytes = readImage(location, path) ?: continue
                    if (saveEncoded(bytes, owner, candidate.source, kinds)) return
                }
            }
        } catch (e: SmbFailure) {
            unreachable = e
        }
        if (mosaic(owner, kinds)) return
        unreachable?.let { throw it }
        placeholder(owner, kinds)
    }

    /**
     * A folder with no picture of its own gets one made out of its contents:
     * [ArtworkCandidates.MOSAIC_CELLS] frames stitched into a grid, the way a
     * photo album shows four of the photos inside it.
     *
     * Written at each kind's own aspect rather than once and cropped: a 16:9
     * grid squeezed into a 2:3 poster would slice the outer columns in half.
     * Same four frames, composed twice — the seeks are the expensive part.
     *
     * All-or-nothing: a grid with a black hole in it looks broken in a way a
     * plain placeholder does not.
     */
    private suspend fun mosaic(owner: ArtworkOwner.Folder, kinds: List<ArtworkKind>): Boolean {
        val files = mosaicFiles(owner.id)
        if (files.isEmpty()) return false
        // Fewer videos than cells: the ones there are each give several
        // frames, so a folder holding one film still reads as that film.
        val slots = IntArray(files.size)
        for (i in 0 until ArtworkCandidates.MOSAIC_CELLS) slots[i % files.size]++

        val cells = mutableListOf<Bitmap>()
        try {
            for ((index, file) in files.withIndex()) {
                openFrames(file)?.use { source ->
                    val duration = runtimeOf(file, source)
                    for (position in ArtworkCandidates.mosaicPositionsMs(duration, slots[index])) {
                        cells += source.frameAt(position, MOSAIC_FRAME_MAX, MOSAIC_FRAME_MAX) ?: return false
                    }
                }
            }
            if (cells.size != ArtworkCandidates.MOSAIC_CELLS) return false
            var wrote = false
            for (kind in kinds) {
                val cellW = kind.width / ArtworkCandidates.MOSAIC_COLUMNS
                val cellH = kind.height / ArtworkCandidates.MOSAIC_ROWS
                val grid = createBitmap(kind.width, kind.height)
                val canvas = Canvas(grid)
                try {
                    cells.forEachIndexed { i, frame ->
                        val cell = ArtworkStore.centerCrop(frame, cellW, cellH)
                        canvas.drawBitmap(
                            cell,
                            (i % ArtworkCandidates.MOSAIC_COLUMNS * cellW).toFloat(),
                            (i / ArtworkCandidates.MOSAIC_COLUMNS * cellH).toFloat(),
                            null,
                        )
                        if (cell !== frame) cell.recycle()
                    }
                    if (store.saveExact(grid, owner, kind)) {
                        record(owner, kind, ArtworkSource.MOSAIC)
                        wrote = true
                    }
                } finally {
                    grid.recycle()
                }
            }
            if (wrote) Log.d(TAG, "mosaic for folder ${owner.id} from ${files.size} file(s)")
            return wrote
        } finally {
            cells.forEach { it.recycle() }
        }
    }

    /**
     * Up to [ArtworkCandidates.MOSAIC_CELLS] videos to draw the mosaic from.
     * A show folder holds seasons rather than files, so this walks down
     * breadth-first until it has enough — that is what makes "Severance"
     * get a tile at all.
     */
    private suspend fun mosaicFiles(folderId: Long): List<MediaFileEntity> {
        val out = mutableListOf<MediaFileEntity>()
        val queue = ArrayDeque(listOf(folderId))
        var visited = 0
        while (queue.isNotEmpty() && out.size < ArtworkCandidates.MOSAIC_CELLS && visited < MOSAIC_MAX_FOLDERS) {
            val id = queue.removeFirst()
            visited++
            out += mediaFileDao.inFolder(id).take(ArtworkCandidates.MOSAIC_CELLS - out.size)
            if (out.size < ArtworkCandidates.MOSAIC_CELLS) queue += folderDao.children(id).map { it.id }
        }
        return out
    }

    /** The file's frames, from a copy on this device if there is one, else the share. */
    private suspend fun openFrames(file: MediaFileEntity): FrameSource? {
        local.file(file.id)?.let { return frames.openLocal(it) }
        val location = locate(file.shareId) ?: return null
        return frames.open(location.host, location.credentials, location.share, file.relPath)
    }

    private suspend fun saveEncoded(bytes: ByteArray, owner: ArtworkOwner, source: ArtworkSource, kinds: List<ArtworkKind>): Boolean {
        var any = false
        for (kind in kinds) {
            if (store.saveEncoded(bytes, owner, kind)) {
                record(owner, kind, source)
                any = true
            }
        }
        return any
    }

    private suspend fun saveBitmap(bitmap: Bitmap, owner: ArtworkOwner, source: ArtworkSource, kinds: List<ArtworkKind>): Boolean {
        var any = false
        for (kind in kinds) {
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

    private suspend fun placeholder(owner: ArtworkOwner, kinds: List<ArtworkKind>) {
        for (kind in kinds) {
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
        /** A mosaic cell is at most 250×375, so this covers it without decoding a full frame per cell. */
        const val MOSAIC_FRAME_MAX = 720
        /** Give up looking for videos after this many folders; a deep tree is not worth a tile. */
        const val MOSAIC_MAX_FOLDERS = 24

        /**
         * How far from the requested position a frame may land before it is
         * worth a warning. A key frame every 10 s is ordinary; a grab that
         * comes back minutes early means the seek was ignored.
         */
        const val SEEK_TOLERANCE_MS = 30_000L
        val BACKDROP_ONLY = listOf(ArtworkKind.BACKDROP)
    }
}
