package com.regolith.player

import android.content.Context
import android.net.ConnectivityManager
import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.DatabaseProvider
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.ContentMetadataMutations
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.inspector.MediaExtractorCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The first seconds of the next Shorts deck's clips, fetched while you are
 * elsewhere in the app and kept on disk, so the tab opens on a clip that
 * plays at once instead of a spinner.
 *
 * Opening a clip on the share costs one to three seconds before its first
 * frame: connect, open the file, read its index, buffer. [ShortsWarmup]
 * knows which clips the next visit opens on; [warmAhead] reads each one's
 * opening [OPENING_MS] into a Media3 [SimpleCache], and the Shorts players
 * read through that cache first ([readThrough]), going to the share only
 * for what comes after.
 *
 * What is kept is exactly what the player will ask for, because it is
 * fetched by running Media3's own extractor over the clip and keeping
 * every byte it read. That matters for phone recordings, which usually
 * write their index (the MP4 `moov` box) at the END of the file: "the first
 * few megabytes" would miss it, and the player would still wait on the
 * share to read it.
 *
 * - Over one connection, without the player's read-ahead
 *   ([SmbDataSource.Factory.withoutReadAhead]): read-ahead fetches a dozen
 *   megabytes past the point where this stops, and would do it just as the
 *   Shorts tab starts streaming the clip on screen.
 * - Only on an unmetered network. A share reached over cellular through a
 *   VPN would otherwise spend tens of megabytes on clips nobody asked for.
 * - Never for a clip with a copy on this device: it already plays from disk.
 * - Keyed by the file's VERSION ([ShortsClip.contentKey]), so a clip
 *   replaced on the share never plays the old one's opening.
 * - [MAX_BYTES] in all, least recently used out first, in the CACHE
 *   directory, which the system may clear: everything here can be fetched
 *   again.
 *
 * Players are deliberately not kept prepared instead: each holds a hardware
 * decoder and its buffers, which would be held while you are elsewhere in
 * the app, competing with the film player. Web analogy: a service worker
 * precaching the first range of the next few videos into Cache Storage, and
 * answering the `<video>` element's range requests from it first.
 */
@UnstableApi
@Singleton
class ShortsOpenings internal constructor(
    dir: File,
    databaseProvider: DatabaseProvider,
    /** The share, read without read-ahead. */
    private val share: DataSource.Factory,
    private val uriFor: (fileId: Long) -> Uri,
    private val onDevice: suspend (fileId: Long) -> Boolean,
    /** Asked before each clip, since the network can change between two. */
    private val unmetered: () -> Boolean,
    private val maxClipBytes: Long = MAX_CLIP_BYTES,
) {
    @Inject constructor(
        @ApplicationContext context: Context,
        smb: SmbDataSource.Factory,
        resolver: MediaUriResolver,
        local: LocalMedia,
    ) : this(
        dir = File(context.cacheDir, DIR),
        databaseProvider = StandaloneDatabaseProvider(context),
        share = smb.withoutReadAhead(),
        uriFor = resolver::uriFor,
        onDevice = { local.file(it) != null },
        unmetered = { isUnmetered(context) },
    )

    /**
     * Opened by the worker as its first step, off the main thread: a
     * SimpleCache reads its index from disk as it opens. There may only
     * ever be one per directory — a second one throws — which is why this
     * class is a singleton.
     */
    private val cache: SimpleCache by lazy { SimpleCache(dir, LeastRecentlyUsedCacheEvictor(MAX_BYTES), databaseProvider) }

    /** Work nobody is waiting on: a failure is logged, never allowed to take the app down. */
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e -> Log.w(TAG, "openings: ${e.javaClass.simpleName}: ${e.message}", e) },
    )

    /** Guards the queue and [attempted]; held only to read or swap them, never across the network. */
    private val lock = Any()
    private var queue: List<ShortsClip> = emptyList()

    /**
     * Clips the worker is done with in this run of the app, fetched or not.
     * Not tried again until asked for anew, so a share that has gone quiet
     * is not asked for the same clip in a loop.
     */
    private val attempted = HashSet<String>()
    private val wake = Channel<Unit>(Channel.CONFLATED)

    private val _handled = MutableStateFlow(0)

    /** Bumps each time the worker is done with a clip, whether it fetched it or passed it over. */
    internal val handled: StateFlow<Int> = _handled.asStateFlow()

    init {
        scope.launch { work() }
    }

    /**
     * Fetch these clips' openings, in this order, replacing the last
     * request: the first clips of the NEXT deck. A clip already on disk is
     * skipped, and one dropped from the list stops where it is.
     */
    fun warmAhead(clips: List<ShortsClip>) {
        synchronized(lock) {
            queue = clips
            // Asked for again, so worth another try.
            attempted.removeAll(clips.map { it.contentKey }.toSet())
        }
        wake.trySend(Unit)
    }

    /**
     * For the Shorts players: each read is answered from the openings on
     * disk when they have it, and from [upstream] when they do not. It never
     * writes — only [warmAhead] fills the cache — so watching a clip to the
     * end does not push the next deck's openings out.
     */
    fun readThrough(upstream: DataSource.Factory): DataSource.Factory =
        CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(upstream)
            .setCacheWriteDataSinkFactory(null)
            // A cache file gone bad costs the head start, never the clip.
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

    /**
     * What a player plays [clip] as, from [uri]: addressed by the clip's
     * version, which is the key its opening was saved under. A copy on this
     * device has the same bytes, so it may use the same opening.
     */
    fun mediaItem(clip: ShortsClip, uri: Uri): MediaItem =
        MediaItem.Builder().setUri(uri).setCustomCacheKey(clip.contentKey).build()

    /** How much of [clip] is on disk, in bytes. */
    fun bytesOnDisk(clip: ShortsClip): Long = cache.getCachedBytes(clip.contentKey, 0, C.LENGTH_UNSET.toLong())

    /** True when [clip]'s opening was fetched whole and none of it has been evicted since. */
    fun isWarm(clip: ShortsClip): Boolean {
        val fetched = cache.getContentMetadata(clip.contentKey).get(OPENING_BYTES, 0L)
        return fetched > 0 && bytesOnDisk(clip) >= fetched
    }

    /** Stops the worker and closes the cache. Tests only: a SimpleCache must be released before another may open its directory. */
    internal fun release() {
        scope.cancel()
        cache.release()
    }

    private suspend fun work() {
        Log.d(TAG, "openings: ${cache.cacheSpace / 1024} KB on disk")
        while (currentCoroutineContext().isActive) {
            val clip = next()
            if (clip == null) {
                wake.receive()
                continue
            }
            try {
                warm(clip)
            } finally {
                synchronized(lock) { attempted += clip.contentKey }
                _handled.update { it + 1 }
            }
        }
    }

    /** The first clip in the queue that is neither on disk nor already tried. */
    private fun next(): ShortsClip? {
        val (clips, tried) = synchronized(lock) { queue to attempted.toSet() }
        return clips.firstOrNull { it.contentKey !in tried && !isWarm(it) }
    }

    private suspend fun warm(clip: ShortsClip) {
        if (onDevice(clip.fileId)) return
        if (!unmetered()) {
            Log.d(TAG, "file ${clip.fileId}: opening not fetched, the network is metered")
            return
        }
        val started = System.nanoTime()
        val context = currentCoroutineContext()
        try {
            val whole = fetch(clip) { context.isActive && synchronized(lock) { clip in queue } }
            val onDisk = bytesOnDisk(clip)
            val ms = (System.nanoTime() - started) / 1_000_000
            if (!whole) {
                // What it read is kept; asked for again, it resumes from disk.
                Log.d(TAG, "file ${clip.fileId}: opening stopped at ${onDisk / 1024} KB, no longer wanted")
                return
            }
            cache.applyContentMetadataMutations(clip.contentKey, ContentMetadataMutations().set(OPENING_BYTES, onDisk))
            Log.d(TAG, "file ${clip.fileId}: opening on disk, ${onDisk / 1024} KB in ${ms}ms")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // One clip's trouble; the queue moves on to the next.
            Log.w(TAG, "file ${clip.fileId}: opening failed (${e.javaClass.simpleName}: ${e.message})")
        }
    }

    /**
     * Runs Media3's extractor — the one the player runs — over [clip],
     * through a cache that keeps every byte it reads, until [OPENING_MS] of
     * samples have gone by, the clip ends, or [maxClipBytes] have been read.
     * Blocking: it checks [wanted] between samples rather than being
     * interrupted, because interrupting a thread inside an SMB read can take
     * the whole connection down with it, and the player may be sharing it.
     *
     * @return false if [wanted] turned false first, so the opening is not whole.
     */
    private fun fetch(clip: ShortsClip, wanted: () -> Boolean): Boolean {
        var read = 0L
        val writing = CacheDataSource.Factory().setCache(cache).setUpstreamDataSourceFactory(share)
        val keyed = DataSource.Factory { KeyedDataSource(writing.createDataSource(), clip.contentKey) { read += it } }
        val extractor = MediaExtractorCompat(DefaultExtractorsFactory(), keyed)
        try {
            extractor.setDataSource(uriFor(clip.fileId), 0)
            for (track in 0 until extractor.trackCount) extractor.selectTrack(track)
            val deadline = System.nanoTime() + CLIP_TIMEOUT_MS * 1_000_000
            // Every sample is walked over but none is copied out: the point is
            // the bytes that go through the cache on the way.
            while (read < maxClipBytes && System.nanoTime() < deadline) {
                if (!wanted()) return false
                val at = extractor.sampleTime
                if (at == -1L || at >= OPENING_MS * 1_000) break
                if (!extractor.advance()) break
            }
            return true
        } finally {
            extractor.release()
        }
    }

    companion object {
        /** How much of each clip: enough to start at once, and to play on while the share is opened behind it. */
        internal const val OPENING_MS = 5_000L

        /**
         * ~5 s of a 40 Mbps clip. A heavier one, 4K at 60 fps, stops earlier
         * with two or three seconds — still enough for the player to start
         * without waiting.
         */
        internal const val MAX_CLIP_BYTES = 24L shl 20

        /** Ten clips at the cap, many more at the bitrates phones record at. */
        private const val MAX_BYTES = 256L shl 20

        /** One clip. A share this slow is not about to deliver; the next clip gets its turn. */
        private const val CLIP_TIMEOUT_MS = 45_000L

        /** Set on a clip once its opening is whole: how many bytes that came to. */
        private const val OPENING_BYTES = "regolith_opening_bytes"

        private const val DIR = "shorts-openings"
        private const val TAG = "Regolith/Shorts"

        /** Wi-Fi or Ethernet, as Android sees it; a VPN reports the network under it. No network counts as metered. */
        private fun isUnmetered(context: Context): Boolean =
            context.getSystemService(ConnectivityManager::class.java)?.isActiveNetworkMetered == false
    }
}

/**
 * Reads [inner] under the cache key [key], counting what passes through.
 * The players get the same key from [ShortsOpenings.mediaItem], which is how
 * the two meet in the cache: Media3 files what it reads under the request's
 * key, and the URI alone does not say which version of a file it was.
 */
@UnstableApi
internal class KeyedDataSource(
    private val inner: DataSource,
    private val key: String,
    private val counted: (Int) -> Unit = {},
) : DataSource by inner {
    override fun open(dataSpec: DataSpec): Long = inner.open(dataSpec.buildUpon().setKey(key).build())

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        inner.read(buffer, offset, length).also { if (it > 0) counted(it) }
}
