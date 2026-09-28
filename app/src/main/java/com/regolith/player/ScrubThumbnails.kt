package com.regolith.player

import android.graphics.Bitmap
import android.util.Log
import androidx.core.graphics.get
import com.regolith.data.artwork.FrameGrabber
import com.regolith.data.artwork.FrameSource
import com.regolith.domain.artwork.ArtworkKind
import com.regolith.domain.playback.FrameIndex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlin.math.abs

/**
 * Preview frames for the seek bar (design section 10). The screen asks for
 * a position while the user drags; frames arrive later, so it also watches
 * [updates] to redraw when one does.
 *
 * The contract fixed in Phase 2. `None` is the implementation when the
 * setting is off or nothing is loaded.
 */
interface ScrubThumbnails {
    /** Bumps every time a frame is added. Collect it and call [nearest] again. */
    val updates: StateFlow<Long>

    /**
     * The best frame already in memory for [positionMs], or null. Cheap;
     * call from the UI.
     *
     * [exact] means "this position's own frame or nothing". Pass it wherever
     * the frame is LABELLED with a time — the chapter sheet, the filmstrip —
     * so a tile can never show a neighbour's picture under someone else's
     * clock. Leave it off for the scrub preview, where a near miss while the
     * finger moves is better than a blank.
     */
    fun nearest(positionMs: Long, exact: Boolean = false): Bitmap?

    /** The user is looking here: load this frame (and its neighbours) if missing. */
    fun request(positionMs: Long)

    /**
     * A wall of frames wanted all at once (the chapter sheet, the flex-mode
     * filmstrip). Every position is loaded, in order; nothing is dropped.
     *
     * Not the same as calling [request] in a loop. That path is built for a
     * finger on the timeline and keeps only the LATEST position, so twelve
     * requests fired together came out as two — the first and the last —
     * and the ten chapters between stayed dark for good.
     */
    fun requestAll(positionsMs: List<Long>)

    fun close()

    object None : ScrubThumbnails {
        override val updates: StateFlow<Long> = MutableStateFlow(0L)
        override fun nearest(positionMs: Long, exact: Boolean): Bitmap? = null
        override fun request(positionMs: Long) = Unit
        override fun requestAll(positionsMs: List<Long>) = Unit
        override fun close() = Unit
    }
}

/**
 * v1: on-demand extraction with an LRU. One worker owns one open
 * [FrameSource] and serves the most recent request first (the channel is
 * conflated, so a fast drag skips the positions the finger passed), then
 * fills in the neighbours around where the finger stopped. Every frame is
 * one key-frame seek over SMB; buckets of 10 s keep the count bounded.
 *
 * **Two extractors, cheapest first.** The platform's
 * `MediaMetadataRetriever` ([com.regolith.data.artwork.RetrieverFrameSource])
 * does the work, because it is by far the cheaper of the two and this path
 * takes dozens of frames. It has one failure the artwork pipeline already
 * had to solve: when it cannot seek a container it does not say so, it
 * hands back the OPENING frame — which is why some films had every chapter
 * tile showing 0:00. So every frame it returns is fingerprinted, and the
 * first time two different positions come back as the same picture the
 * source is written off: what it has given us so far is dropped and the
 * rest of the file is taken from Media3's `FrameExtractor` ([grabber]),
 * which uses the same extractors as playback and, unlike the retriever,
 * states which frame it actually gave you. A frame whose receipt is more
 * than [SEEK_TOLERANCE_MS] from what was asked for is thrown away rather
 * than shown under the wrong clock.
 *
 * The catch is a file asked for exactly one frame: with nothing to compare
 * against, a bad seek goes unnoticed. Both callers ask for several (the
 * scrub prefetch takes five, the chapter sheet takes one per part), so in
 * practice the clash shows up on the second frame.
 *
 * v2 (a pre-generated sheet for titles the user has started) would sit
 * behind the same interface.
 */
class OnDemandScrubThumbnails(
    private val durationMs: Long,
    /** Which file, for [grabber] — it opens the file itself rather than sharing the handle. */
    private val fileId: Long,
    /** Media3's extractor, used only once the platform one is caught lying. Null turns the fallback off. */
    private val grabber: FrameGrabber?,
    private val openSource: () -> FrameSource,
) : ScrubThumbnails {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /** Where the finger is. Conflated: a fast drag skips the positions it passed. */
    private val requests = Channel<Long>(Channel.CONFLATED)
    /** Frames wanted one by one, none skipped. Served whenever the finger is still. */
    private val batch = Channel<Long>(Channel.UNLIMITED)
    private val index = FrameIndex<Bitmap>(onEvict = { it.recycle() })
    @Volatile private var latestRequestMs = -1L
    private val _updates = MutableStateFlow(0L)
    override val updates: StateFlow<Long> = _updates.asStateFlow()

    /** Frames read from the UI thread while the worker writes: a plain lock, held briefly. */
    private val readLock = Any()

    /** Fingerprints of the pictures the platform retriever handed back, by bucket. Worker thread only. */
    private val prints = HashMap<Long, Int>()
    /** Buckets neither extractor could place. Never asked for a second time. Worker thread only. */
    private val giveUp = HashSet<Long>()
    /** Cleared the first time the platform retriever returns one picture for two positions. Worker thread only. */
    private var retrieverSeeks = true

    init {
        scope.launch { work() }
    }

    override fun nearest(positionMs: Long, exact: Boolean): Bitmap? =
        synchronized(readLock) { if (exact) index.nearest(positionMs, tolerance = 0) else index.nearest(positionMs) }

    override fun request(positionMs: Long) {
        latestRequestMs = positionMs
        requests.trySend(positionMs)
    }

    override fun requestAll(positionsMs: List<Long>) {
        positionsMs.forEach { batch.trySend(it) }
    }

    override fun close() {
        scope.cancel()
        requests.close()
        batch.close()
        synchronized(readLock) { index.clear() }
    }

    private suspend fun work() {
        var source: FrameSource? = null
        try {
            while (true) {
                // Two queues, one worker. `select` is biased to its first
                // clause, so a finger on the timeline is always served before
                // the next tile of a wall that can wait.
                val job = select<Want?> {
                    requests.onReceiveCatching { r -> r.getOrNull()?.let { Want(it, radius = PREFETCH_RADIUS) } }
                    batch.onReceiveCatching { r -> r.getOrNull()?.let { Want(it, radius = 0) } }
                } ?: return
                val src = source ?: try {
                    openSource().also { source = it; Log.d(TAG, "source open: ${it.width}x${it.height} ${it.durationMs}ms") }
                } catch (e: Exception) {
                    Log.w(TAG, "scrub source failed to open: ${e.message}")
                    return
                }
                // The player may not know the runtime yet when the file is loaded; the container does.
                val runtime = if (durationMs > 0) durationMs else src.durationMs ?: 0L
                val maxBucket = index.bucketOf(runtime)
                val positionMs = job.positionMs
                val wanted = synchronized(readLock) { index.missingAround(positionMs, radius = job.radius, maxBucket = maxBucket) }
                for (bucket in wanted) {
                    // A newer request wins over prefetching the neighbours of an old one.
                    if (bucket != index.bucketOf(positionMs) && latestRequestMs != positionMs) break
                    if (bucket in giveUp) continue
                    val started = System.nanoTime()
                    val frame = grab(src, bucket)
                    Log.d(TAG, "bucket $bucket (${index.positionOf(bucket)}ms): ${if (frame == null) "no frame" else "${frame.width}x${frame.height}"} in ${(System.nanoTime() - started) / 1_000_000}ms")
                    if (frame == null) continue
                    synchronized(readLock) { index.put(bucket, frame) }
                    _updates.value++
                }
            }
        } finally {
            source?.close()
        }
    }

    /**
     * One bucket's picture, from whichever extractor can still be trusted.
     *
     * The platform retriever gets first refusal because it is cheap, and is
     * checked by fingerprint: the same picture for two different positions
     * means it never seeked, and everything it has said so far is worthless.
     * Media3 is the fallback, and it is checked by receipt.
     */
    private suspend fun grab(src: FrameSource, bucket: Long): Bitmap? {
        val positionMs = index.positionOf(bucket)
        if (retrieverSeeks) {
            val frame = src.frameAt(positionMs, ArtworkKind.THUMB.width, ArtworkKind.THUMB.height)
            if (frame != null) {
                val print = fingerprint(frame)
                val clash = print?.let { p -> prints.entries.firstOrNull { it.value == p && it.key != bucket } }
                if (clash == null) {
                    if (print != null) prints[bucket] = print
                    return frame
                }
                Log.w(TAG, "retriever gave bucket $bucket the same picture as bucket ${clash.key}: it is not seeking, falling back to media3")
                frame.recycle()
                retrieverSeeks = false
                prints.clear()
                // Everything it handed over is the same wrong picture. Drop it
                // WITHOUT recycling: the tiles on screen are still drawing it.
                synchronized(readLock) { index.forget() }
                _updates.value++
            }
        }
        val grabbed = grabber?.frameAt(fileId, positionMs, ArtworkKind.THUMB.width, ArtworkKind.THUMB.height) ?: return null
        val off = grabbed.presentationTimeMs - positionMs
        if (abs(off) > SEEK_TOLERANCE_MS) {
            // The receipt says this frame is from somewhere else entirely.
            // A dark tile is better than one lying about what it shows, and
            // asking again would only get the same answer.
            Log.w(TAG, "bucket $bucket: asked for ${positionMs}ms, media3 gave ${grabbed.presentationTimeMs}ms (${off}ms off) — dropped")
            grabbed.bitmap.recycle()
            giveUp += bucket
            return null
        }
        return grabbed.bitmap
    }

    /**
     * A cheap hash of the picture: a grid of pixels, not every one of them.
     *
     * It only has to answer "is this the very same frame as that one?", and
     * two frames from different places in a film do not agree on 400-odd
     * sampled pixels. Null when the bitmap cannot be read pixel by pixel —
     * then nothing is concluded, and the retriever keeps its benefit of the
     * doubt.
     */
    private fun fingerprint(bitmap: Bitmap): Int? = runCatching {
        val stepX = (bitmap.width / FINGERPRINT_GRID).coerceAtLeast(1)
        val stepY = (bitmap.height / FINGERPRINT_GRID).coerceAtLeast(1)
        var hash = 17
        var y = 0
        while (y < bitmap.height) {
            var x = 0
            while (x < bitmap.width) {
                hash = hash * 31 + bitmap[x, y]
                x += stepX
            }
            y += stepY
        }
        hash
    }.getOrNull()

    /** One thing to fetch: the position, and how many neighbouring buckets to fill in around it. */
    private class Want(val positionMs: Long, val radius: Int)

    private companion object {
        const val TAG = "Regolith/Scrub"
        const val PREFETCH_RADIUS = 2

        /**
         * How far a frame may sit from the position asked for before it is
         * thrown away rather than shown.
         *
         * Generous, because `CLOSEST_SYNC` legitimately lands on the key
         * frame BEFORE the request and key frames can be ten seconds or more
         * apart — but well inside the closest two marks can ever be
         * ([com.regolith.domain.playback.ChapterMarks.intervalsMs] starts at
         * 10 s), so a frame from the wrong part never passes.
         */
        const val SEEK_TOLERANCE_MS = 2 * FrameIndex.DEFAULT_INTERVAL_MS

        /** Pixels sampled per axis for [fingerprint]: 20x20 of a 320x180 tile. */
        const val FINGERPRINT_GRID = 20
    }
}
