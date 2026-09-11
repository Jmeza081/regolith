package com.regolith.player

import android.graphics.Bitmap
import android.util.Log
import com.regolith.data.artwork.FrameSource
import com.regolith.data.artwork.FrameSourceFactory
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

    /** The best frame already in memory for [positionMs], or null. Cheap; call from the UI. */
    fun nearest(positionMs: Long): Bitmap?

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
        override fun nearest(positionMs: Long): Bitmap? = null
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
 * v2 (a pre-generated sheet for titles the user has started) would sit
 * behind the same interface.
 */
class OnDemandScrubThumbnails(
    private val durationMs: Long,
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

    init {
        scope.launch { work() }
    }

    override fun nearest(positionMs: Long): Bitmap? = synchronized(readLock) { index.nearest(positionMs) }

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
                    val started = System.nanoTime()
                    val frame = src.frameAt(index.positionOf(bucket), ArtworkKind.THUMB.width, ArtworkKind.THUMB.height)
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

    /** One thing to fetch: the position, and how many neighbouring buckets to fill in around it. */
    private class Want(val positionMs: Long, val radius: Int)

    private companion object {
        const val TAG = "Regolith/Scrub"
        const val PREFETCH_RADIUS = 2
    }
}
