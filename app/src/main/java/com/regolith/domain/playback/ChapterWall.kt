package com.regolith.domain.playback

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch

/**
 * The chapter sheet's pictures, and whether the sheet is ready to open.
 *
 * [frames] is keyed by each chapter's START, which is what the sheet draws a
 * tile for, whatever time the picture was taken from ([ChapterStills]).
 * [complete] is the Chapters pill's answer: every tile has its picture, or a
 * picture standing in for it, or there is no picture of this film anywhere.
 *
 * Generic in the picture so the rules can be tested without Android: the app
 * fills it with bitmaps, a test with strings.
 */
data class ChapterWall<P>(val frames: Map<Long, P>, val complete: Boolean) {
    companion object {
        /** Nothing yet, and the pill spins. */
        fun <P> waiting() = ChapterWall<P>(emptyMap(), complete = false)

        /** Ready, with no pictures to show: no chapters, or a film with nowhere to keep a frame. */
        fun <P> withoutPictures() = ChapterWall<P>(emptyMap(), complete = true)
    }
}

/** What the cache holds for one frame. */
sealed interface CachedStill<out P> {
    /** A picture to draw: the frame, or the film's own picture the cache put in its place. */
    data class Picture<P>(val picture: P) : CachedStill<P>

    /** Tried, and nothing to draw: no frame, and no picture of the film to stand in. */
    data object Nothing : CachedStill<kotlin.Nothing>
}

/**
 * Where a chapter wall's pictures come from: the artwork cache in the app
 * (`com.regolith.data.artwork.ArtworkChapterPictures`), anything in a test.
 */
interface ChapterPictures<P> {
    /** What is cached for the frame at [timeMs] in film [fileId]; null when nothing has been made for it yet. */
    suspend fun cached(fileId: Long, timeMs: Long): CachedStill<P>?

    /**
     * Make the frames at [timesMs], on as few opens of the file as it takes.
     * Returns when the run is over, whatever it managed. A frame that could
     * not be had is cached as the film's own picture. One the share was too
     * unwell to try is not cached at all, so it can be asked for again.
     */
    suspend fun make(fileId: Long, timesMs: List<Long>)

    /** The film's own picture, to stand in for a frame still missing; null when it has none. Never waits on the share. */
    suspend fun standIn(fileId: Long): P?
}

/**
 * Fills a chapter wall, and says when it is full.
 *
 * The sheet is a wall of stills, a way to SEE where to go, and a sheet that
 * opens on blank tiles has failed at the one thing it is for. So the pill
 * waits until every tile has a picture, and every way this can go wrong ends
 * in a picture rather than a blank:
 *
 *  1. The cache first. Frames are kept on disk, so a film opened before has
 *     its wall before its first frame plays.
 *  2. The rest on ONE open of the file ([ChapterPictures.make]). A frame that
 *     cannot be had comes back as the film's own picture, which at least does
 *     not claim to be somewhere it is not.
 *  3. A run that stalls (no new frame for [STALL_MS]) or runs past
 *     [CEILING_MS] opens the sheet with the film's picture standing in for
 *     what is still missing. Frames that land afterwards take their places.
 *  4. A frame the share was too unwell to try is asked for again, up to
 *     [RETRIES] times, further apart each time. Its stand-in holds meanwhile.
 *
 * A tile stays blank only for a film with no picture at all (no frame, no
 * poster), which has nothing to show anywhere else in the app either.
 */
class ChapterWallLoader<P>(private val pictures: ChapterPictures<P>) {

    /** The wall for [chapters] of film [fileId], updated as it fills; finishes once nothing more is coming. */
    fun load(fileId: Long, chapters: List<Chapter>, durationMs: Long, source: ChapterSource): Flow<ChapterWall<P>> = channelFlow {
        if (chapters.isEmpty()) {
            send(ChapterWall.withoutPictures())
            return@channelFlow
        }
        // (chapter start, the time its picture is taken from)
        val wanted = chapters.map { it.startMs }.zip(ChapterStills.timesFor(chapters, durationMs, source))
        val own = HashMap<Long, P>()
        // Starts whose cache entry is final, a picture or Nothing.
        val settled = HashSet<Long>()
        var standIn: P? = null
        var standInRead = false

        /** True when anything new came out of the cache. */
        suspend fun readCache(): Boolean {
            var changed = false
            for ((start, at) in wanted) {
                if (start in settled) continue
                when (val still = pictures.cached(fileId, at)) {
                    null -> continue
                    is CachedStill.Picture -> own[start] = still.picture
                    CachedStill.Nothing -> Unit
                }
                settled += start
                changed = true
            }
            return changed
        }

        // Once the wall opens, a tile without its own picture shows the film's.
        suspend fun wall(open: Boolean): ChapterWall<P> {
            if (!open) return ChapterWall(own.toMap(), complete = false)
            if (!standInRead) {
                standIn = pictures.standIn(fileId)
                standInRead = true
            }
            val fill = standIn ?: return ChapterWall(own.toMap(), complete = true)
            return ChapterWall(wanted.associate { (start, _) -> start to (own[start] ?: fill) }, complete = true)
        }

        readCache()
        if (settled.size == wanted.size) {
            send(wall(open = true))
            return@channelFlow
        }
        send(wall(open = false))

        var opened = false
        var attempt = 0
        while (true) {
            val missing = wanted.filter { it.first !in settled }.map { it.second }
            if (missing.isEmpty()) break
            val run = launch { pictures.make(fileId, missing) }
            var elapsed = 0L
            var sinceLast = 0L
            while (run.isActive) {
                delay(POLL_MS)
                elapsed += POLL_MS
                sinceLast += POLL_MS
                if (readCache()) {
                    sinceLast = 0L
                    if (opened) send(wall(open = true))
                }
                if (!opened && (sinceLast >= STALL_MS || elapsed >= CEILING_MS)) {
                    opened = true
                    send(wall(open = true))
                }
            }
            readCache()
            if (settled.size == wanted.size) break
            // The run is over and the share failed some frames: open on their
            // stand-ins now, and ask again in a while.
            if (!opened) {
                opened = true
                send(wall(open = true))
            }
            if (++attempt > RETRIES) break
            delay(RETRY_MS shl (attempt - 1))
        }
        send(wall(open = true))
    }

    companion object {
        /** How often a run in progress is checked for new frames. */
        const val POLL_MS = 250L

        /**
         * No new frame for this long and the run is stuck: the sheet opens on
         * stand-ins. Well above a slow first frame (opening the file, reading
         * its index and setting up a decoder takes a few seconds over SMB),
         * and below Media3's 30 s per frame, which only a wedged decoder hits.
         */
        const val STALL_MS = 15_000L

        /**
         * However busy it looks. A film with dozens of chapters on a slow share
         * may get here, and its last frames swap in after the sheet opens.
         */
        const val CEILING_MS = 60_000L

        /** How many more runs a frame the share failed gets. */
        const val RETRIES = 3

        /** The wait before the first of those, doubling each time after. */
        const val RETRY_MS = 5_000L
    }
}
