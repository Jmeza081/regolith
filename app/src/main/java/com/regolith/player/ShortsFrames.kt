package com.regolith.player

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.regolith.data.artwork.FrameGrabber
import com.regolith.data.prefs.AppPreferences
import com.regolith.domain.playback.Filmstrip
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectIndexed
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.Collections
import java.util.IdentityHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One short, as far as its strip is concerned: which file, which VERSION of
 * it — a clip replaced on the share under the same name gets new frames —
 * and how long it runs, which decides where the frames are taken.
 */
data class ShortsClip(val fileId: Long, val sizeBytes: Long, val modifiedAtMs: Long, val durationMs: Long) {
    /** The clip's folder name on disk: everything a frame depends on. */
    internal val key: String get() = "$fileId-$sizeBytes-$modifiedAtMs-$durationMs"
}

/**
 * The Shorts panel's filmstrips, made BEFORE they are looked at and kept on
 * disk.
 *
 * Read off the share while you watched, a strip took seconds to fill: open
 * the file, seek eight times, decode eight key frames. So they are made
 * ahead instead, by one worker, one clip at a time, in two queues:
 *
 * - **now** ([warmNow]): the clip the panel is showing and the next two,
 *   so the strip is ready before you swipe to it. First, always; a new
 *   request interrupts anything from the other queue.
 * - **ahead** ([warmAhead]): the first clips of the NEXT deck
 *   ([ShortsWarmup]), made while you are somewhere else in the app, so the
 *   strip of the very first clip is there the moment Shorts opens.
 *
 * Each clip is one open of the file for all eight frames
 * ([FrameGrabber.framesAt], the same batch the chapter stills use). The
 * frames are small JPEGs in the app's CACHE directory — the system may
 * clear it, and every frame can be made again — one folder per
 * [ShortsClip.key], about 100 KB a clip, the oldest folders going past
 * [MAX_CLIPS]. Web analogy: a service worker precaching the next page's
 * images into Cache Storage.
 *
 * Off when Settings › Playback › Scrub thumbnails is: the strip is then a
 * row of blanks that still jump when tapped.
 */
@Singleton
class ShortsFrames @Inject constructor(
    @ApplicationContext context: Context,
    private val grabber: FrameGrabber,
    private val prefs: AppPreferences,
) {
    private val root = File(context.cacheDir, DIR)

    /**
     * Anything that escapes a clip is logged, not rethrown. This is work
     * nobody asked for at the moment it runs — the next deck, made at startup
     * — and a coroutine failing with no handler takes the whole app down,
     * which is exactly what an unexpected bitmap did to the first build of it.
     */
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e -> Log.w(TAG, "frames: ${e.javaClass.simpleName}: ${e.message}", e) },
    )

    /**
     * Guards the two queues and what the worker is on. A plain lock, held
     * only to read or swap a few fields — never across the disk — so the
     * panel can hand over a request from the main thread and have it in
     * place before the call returns.
     */
    private val lock = Any()
    private var now: List<ShortsClip> = emptyList()
    private var ahead: List<ShortsClip> = emptyList()

    /** Bumped by every [warmNow], so the worker can tell a request arrived while it was choosing. */
    private var nowVersion = 0L

    /**
     * Clips that came out incomplete in this run of the app. Not tried again
     * until asked for anew, so a share that has gone quiet is not asked for
     * the same frames in a loop.
     */
    private val attempted = HashSet<String>()
    private var working: Job? = null
    private var workingAhead = false
    private val wake = Channel<Unit>(Channel.CONFLATED)

    private val _updates = MutableStateFlow(0L)

    /** Bumps every time a frame lands on disk. Collect it and read [files] again. */
    val updates: StateFlow<Long> = _updates.asStateFlow()

    init {
        scope.launch { work() }
    }

    /** Where [clip]'s frames are taken, in order: the middle of each of [STRIP_FRAMES] slices. */
    fun positions(clip: ShortsClip): List<Long> = Filmstrip.positions(clip.durationMs, STRIP_FRAMES)

    /** One entry per [positions]: the frame's file once it has been made, else null. Reads the disk: call off the main thread. */
    fun files(clip: ShortsClip): List<File?> {
        val dir = dirFor(clip)
        return positions(clip).map { at -> fileFor(dir, at).takeIf { it.exists() } }
    }

    /** Make these first, in this order: the clip on screen, then the ones after it. Replaces the last such request. */
    fun warmNow(clips: List<ShortsClip>) {
        synchronized(lock) {
            now = clips
            nowVersion++
            // Asked for again, so worth another try.
            attempted.removeAll(clips.map { it.key }.toSet())
            // What is on screen outranks the next deck: stop that clip here.
            // Its finished frames are kept, and the rest are made when the
            // queue comes back round to it.
            if (clips.isNotEmpty() && workingAhead) working?.cancel()
        }
        // Shown, so it is the last thing to be pruned.
        clips.firstOrNull()?.let { clip -> scope.launch { dirFor(clip).setLastModified(System.currentTimeMillis()) } }
        wake.trySend(Unit)
    }

    /** Make these when nothing on screen is waiting: the next deck's first clips. Replaces the last such request. */
    fun warmAhead(clips: List<ShortsClip>) {
        synchronized(lock) { ahead = clips }
        wake.trySend(Unit)
    }

    private suspend fun work() {
        while (true) {
            val version = synchronized(lock) { nowVersion }
            val next = pick()
            if (next == null) {
                wake.receive()
                continue
            }
            val (clip, isAhead) = next
            // Registered before it starts, so a request arriving in between
            // can already interrupt it — and not started at all if the panel
            // asked for something while this was being chosen.
            val job = scope.launch(start = CoroutineStart.LAZY) { make(clip) }
            val stale = synchronized(lock) {
                working = job
                workingAhead = isAhead
                isAhead && nowVersion != version && now.isNotEmpty()
            }
            if (stale) job.cancel() else job.start()
            job.join()
            synchronized(lock) {
                working = null
                workingAhead = false
                // Interrupted is not attempted: it resumes where it stopped.
                if (!job.isCancelled) attempted += clip.key
            }
        }
    }

    /**
     * The first clip, from the more urgent queue, that is neither finished
     * nor already tried. The queues are copied under the lock and checked
     * against the disk outside it.
     */
    private fun pick(): Pair<ShortsClip, Boolean>? {
        val (urgent, later, tried) = synchronized(lock) { Triple(now, ahead, attempted.toSet()) }
        urgent.firstOrNull { it.key !in tried && !complete(it) }?.let { return it to false }
        later.firstOrNull { it.key !in tried && !complete(it) }?.let { return it to true }
        return null
    }

    private fun complete(clip: ShortsClip): Boolean = files(clip).all { it != null }

    private suspend fun make(clip: ShortsClip) {
        if (!prefs.scrubThumbnails.first()) return
        val dir = dirFor(clip).apply { mkdirs() }
        val missing = positions(clip).filterNot { fileFor(dir, it).exists() }
        if (missing.isEmpty()) return
        val started = System.nanoTime()
        var made = 0
        // Recycled when the clip is done, not as each frame is written: a
        // grabber may hand the same bitmap over for two positions (two seeks
        // landing on one key frame), and recycling it after the first write
        // would leave the second writing a recycled bitmap.
        val taken = Collections.newSetFromMap(IdentityHashMap<Bitmap, Boolean>())
        try {
            withTimeoutOrNull(CLIP_TIMEOUT_MS) {
                grabber.framesAt(clip.fileId, missing, FRAME_WIDTH, FRAME_HEIGHT).collectIndexed { i, grabbed ->
                    val frame = grabbed ?: return@collectIndexed
                    taken += frame.bitmap
                    if (frame.bitmap.isRecycled) return@collectIndexed
                    write(frame.bitmap, fileFor(dir, missing[i]))
                    made++
                    _updates.update { it + 1 }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // One clip's trouble; the queue moves on to the next.
            Log.w(TAG, "file ${clip.fileId}: frames failed after $made (${e.javaClass.simpleName}: ${e.message})")
        } finally {
            taken.forEach { it.recycle() }
        }
        dir.setLastModified(System.currentTimeMillis())
        Log.d(TAG, "file ${clip.fileId}: $made of ${missing.size} frames in ${(System.nanoTime() - started) / 1_000_000}ms")
        prune()
    }

    /** Written beside the target and renamed into place, so a half-written frame is never shown. */
    private fun write(bitmap: Bitmap, target: File) {
        val part = File(target.parentFile, target.name + ".part")
        part.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
        if (!part.renameTo(target)) part.delete()
    }

    /** Keep the [MAX_CLIPS] most recently made or shown; the rest go. */
    private fun prune() {
        val dirs = root.listFiles()?.filter { it.isDirectory } ?: return
        if (dirs.size <= MAX_CLIPS) return
        dirs.sortedBy { it.lastModified() }.take(dirs.size - MAX_CLIPS).forEach { it.deleteRecursively() }
    }

    private fun dirFor(clip: ShortsClip) = File(root, clip.key)

    private fun fileFor(dir: File, positionMs: Long) = File(dir, "$positionMs.jpg")

    companion object {
        /** Eight across: at half the inner display each frame is ~50dp, enough to tell one moment from another. */
        const val STRIP_FRAMES = 8

        /** A portrait box, because a short stands up: a 16:9 box would shrink each frame to a sliver. */
        private const val FRAME_WIDTH = 180
        private const val FRAME_HEIGHT = 320
        private const val JPEG_QUALITY = 82

        /** ~100 KB each, so ~20 MB at most, in a directory the system may clear. */
        internal const val MAX_CLIPS = 200

        /** One clip's whole batch. A share this slow is not about to deliver; the next clip gets its turn. */
        private const val CLIP_TIMEOUT_MS = 30_000L

        private const val DIR = "shorts-frames"
        private const val TAG = "Regolith/ShortsFrames"
    }
}
