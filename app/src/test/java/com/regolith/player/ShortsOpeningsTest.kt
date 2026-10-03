package com.regolith.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.inspector.MediaExtractorCompat
import androidx.media3.inspector.MetadataRetriever
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.regolith.R
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.util.Collections
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * The Shorts openings: what is fetched ahead of time, and that the players
 * then find it on disk — a phone recording's index at the END of the file
 * included — without asking the share for any of it.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class ShortsOpeningsTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    /**
     * A 20 s clip, video and audio, with its `moov` after its `mdat`, the way
     * phones record. Its `mdat` is deliberately over 256 KB: below that,
     * Media3 reads straight through to the index instead of jumping to it,
     * and the whole clip would land on disk — which no real clip is small
     * enough to do.
     */
    private val indexAtEnd: ByteArray by lazy { javaClass.getResourceAsStream("/shorts/index_at_end.mp4")!!.readBytes() }

    /** The demo library's 20 s vertical clip, with its `moov` first. */
    private val indexAtStart: ByteArray by lazy { context.resources.openRawResource(R.raw.demo_vertical).use { it.readBytes() } }

    /** The share: one file per id. */
    private val files = HashMap<Long, ByteArray>()

    /** Every open of a file on the share, by id, in order. */
    private val opened = Collections.synchronizedList(mutableListOf<Long>())

    /** Every byte the share has handed over. */
    private val fromShare = AtomicLong()

    /** Files the share fails to open. Read on Media3's loading threads, so synchronized. */
    private val failFor: MutableSet<Long> = Collections.synchronizedSet(mutableSetOf())
    private val onDevice = mutableSetOf<Long>()
    @Volatile private var unmetered = true

    private val made = mutableListOf<ShortsOpenings>()

    @After
    fun releaseCaches() = made.forEach { it.release() }

    private fun openings(maxClipBytes: Long = ShortsOpenings.MAX_CLIP_BYTES) = ShortsOpenings(
        dir = File(context.cacheDir, "openings-${System.nanoTime()}"),
        databaseProvider = StandaloneDatabaseProvider(context),
        share = { ShareFile() },
        uriFor = ::uriFor,
        onDevice = { it in onDevice },
        unmetered = { unmetered },
        maxClipBytes = maxClipBytes,
    ).also { made += it }

    private fun uriFor(fileId: Long): Uri = Uri.parse("regolith://file/$fileId")

    private fun clip(id: Long, bytes: ByteArray = indexAtEnd, modifiedAtMs: Long = 1_000): ShortsClip {
        files[id] = bytes
        return ShortsClip(fileId = id, sizeBytes = bytes.size.toLong(), modifiedAtMs = modifiedAtMs, durationMs = 20_000)
    }

    /** Until the worker has finished with [count] clips in all, fetched or passed over. */
    private fun ShortsOpenings.awaitHandled(count: Int) = runBlocking { withTimeout(20_000) { handled.first { it >= count } } }

    /**
     * Prepares [clip] the way a Shorts player does — the same media source,
     * reading through [ShortsOpenings.readThrough] under the key from
     * [ShortsOpenings.mediaItem] — and returns its duration and how many
     * bytes the share handed over meanwhile.
     */
    private fun prepareLikeThePlayer(openings: ShortsOpenings, clip: ShortsClip): Pair<Long, Long> {
        val before = fromShare.get()
        val sources = DefaultMediaSourceFactory(context).setDataSourceFactory(openings.readThrough { ShareFile() })
        val durationUs = MetadataRetriever.Builder(context, openings.mediaItem(clip, uriFor(clip.fileId)))
            .setMediaSourceFactory(sources)
            .build()
            // Bounded: with the share gone and the clip not on disk, Media3
            // retries without end, and an unbounded get() would hang CI.
            .use { it.retrieveDurationUs().get(15, TimeUnit.SECONDS) }
        return durationUs to fromShare.get() - before
    }

    /**
     * Prepares [clip] as [prepareLikeThePlayer] does, but with the share gone,
     * and returns its duration — which it can only find if everything the
     * player needs to get ready is on disk.
     *
     * Not "how many bytes did the share hand over": once ready, the player's
     * loader keeps reading ahead on its own thread, and how far it gets before
     * it is stopped depends on the machine. For a clip with its index at the
     * front, a CI runner read 43 KB past the opening that a Mac never reached.
     */
    private fun prepareWithoutTheShare(openings: ShortsOpenings, clip: ShortsClip): Long {
        failFor += clip.fileId
        try {
            return prepareLikeThePlayer(openings, clip).first
        } finally {
            failFor -= clip.fileId
        }
    }

    /** Reads [clip]'s samples up to [untilMs] the way the player's extractor does; returns the bytes the share handed over. */
    private fun playLikeThePlayer(openings: ShortsOpenings, clip: ShortsClip, untilMs: Long): Long {
        val before = fromShare.get()
        val reads = openings.readThrough { ShareFile() }
        val extractor = MediaExtractorCompat(DefaultExtractorsFactory()) { KeyedDataSource(reads.createDataSource(), clip.contentKey) }
        try {
            extractor.setDataSource(uriFor(clip.fileId), 0)
            for (track in 0 until extractor.trackCount) extractor.selectTrack(track)
            while (extractor.sampleTime in 0 until untilMs * 1_000) extractor.advance()
        } finally {
            extractor.release()
        }
        return fromShare.get() - before
    }

    @Test
    fun `a phone recording's opening is kept index and all, and the player prepares it from disk`() {
        val openings = openings()
        val clip = clip(1)
        assertFalse("nothing yet", openings.isWarm(clip))
        openings.warmAhead(listOf(clip))
        openings.awaitHandled(1)
        assertTrue(openings.isWarm(clip))
        assertTrue("the opening, not the whole clip: ${openings.bytesOnDisk(clip)} of ${indexAtEnd.size}", openings.bytesOnDisk(clip) < indexAtEnd.size / 2)

        assertEquals("duration, in ms", 20_000.0, prepareWithoutTheShare(openings, clip) / 1_000.0, 100.0)
    }

    @Test
    fun `the first seconds play from disk, and the share is read only after them`() {
        val openings = openings()
        val clip = clip(1)
        openings.warmAhead(listOf(clip))
        openings.awaitHandled(1)
        assertEquals("share bytes for the first 4.5 s", 0L, playLikeThePlayer(openings, clip, untilMs = 4_500))
        assertTrue("the rest still comes from the share", playLikeThePlayer(openings, clip, untilMs = 12_000) > 0)
    }

    @Test
    fun `a clip with its index at the front is kept too`() {
        val openings = openings()
        val clip = clip(2, indexAtStart)
        openings.warmAhead(listOf(clip))
        openings.awaitHandled(1)
        assertTrue(openings.isWarm(clip))
        assertEquals("duration, in ms", 20_000.0, prepareWithoutTheShare(openings, clip) / 1_000.0, 100.0)
        assertEquals(0L, playLikeThePlayer(openings, clip, untilMs = 4_500))
    }

    @Test
    fun `a clip nobody warmed is read from the share as before`() {
        val openings = openings()
        val clip = clip(3)
        assertTrue(prepareLikeThePlayer(openings, clip).second > 0)
        assertTrue(playLikeThePlayer(openings, clip, untilMs = 4_500) > 0)
    }

    @Test
    fun `a clip replaced on the share is fetched again, never played from the old one's opening`() {
        val openings = openings()
        val old = clip(1, modifiedAtMs = 1_000)
        openings.warmAhead(listOf(old))
        openings.awaitHandled(1)
        val replaced = clip(1, modifiedAtMs = 2_000)
        assertFalse("same file, new version: nothing for it yet", openings.isWarm(replaced))
        assertTrue(prepareLikeThePlayer(openings, replaced).second > 0)

        openings.warmAhead(listOf(replaced))
        openings.awaitHandled(2)
        assertTrue(openings.isWarm(replaced))
    }

    @Test
    fun `a clip already on disk is not fetched again`() {
        val openings = openings()
        val first = clip(1)
        openings.warmAhead(listOf(first))
        openings.awaitHandled(1)
        val opensOfFirst = opened.count { it == 1L }

        openings.warmAhead(listOf(first, clip(2)))
        openings.awaitHandled(2)
        assertEquals(opensOfFirst, opened.count { it == 1L })
        assertTrue(opened.contains(2L))
    }

    @Test
    fun `a clip on this device, or any clip on a metered network, is not fetched`() {
        val openings = openings()
        onDevice += 1L
        openings.warmAhead(listOf(clip(1)))
        openings.awaitHandled(1)

        unmetered = false
        openings.warmAhead(listOf(clip(2)))
        openings.awaitHandled(2)

        assertTrue("the share was never asked: $opened", opened.isEmpty())
    }

    @Test
    fun `a clip that fails does not stop the next one, or the app`() {
        val openings = openings()
        failFor += 99L
        openings.warmAhead(listOf(clip(99), clip(100)))
        openings.awaitHandled(2)
        assertFalse(openings.isWarm(clip(99)))
        assertTrue(openings.isWarm(clip(100)))
    }

    @Test
    fun `a heavy clip stops at the byte cap, and what it got still counts`() {
        val openings = openings(maxClipBytes = 16 * 1024)
        val clip = clip(1)
        openings.warmAhead(listOf(clip))
        openings.awaitHandled(1)
        assertTrue(openings.isWarm(clip))
        // The cap, give or take the one sample that was being read when it
        // was reached; five seconds of this clip would be ~100 KB.
        assertTrue("${openings.bytesOnDisk(clip)} bytes on disk", openings.bytesOnDisk(clip) < 32 * 1024)
    }

    /** One open of a file on the fake share: honours position and length, and counts what it hands over. */
    private inner class ShareFile : BaseDataSource(/* isNetwork = */ true) {
        private var uri: Uri? = null
        private var data = ByteArray(0)
        private var position = 0
        private var remaining = 0L

        override fun open(dataSpec: DataSpec): Long {
            val id = dataSpec.uri.lastPathSegment!!.toLong()
            if (id in failFor) throw IOException("the share went away")
            transferInitializing(dataSpec)
            data = files.getValue(id)
            opened += id
            uri = dataSpec.uri
            position = dataSpec.position.toInt()
            remaining = if (dataSpec.length == C.LENGTH_UNSET.toLong()) (data.size - position).toLong() else dataSpec.length
            transferStarted(dataSpec)
            return remaining
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            val n = minOf(length.toLong(), remaining, (data.size - position).toLong()).toInt()
            if (n <= 0) return C.RESULT_END_OF_INPUT
            System.arraycopy(data, position, buffer, offset, n)
            position += n
            remaining -= n
            fromShare.addAndGet(n.toLong())
            bytesTransferred(n)
            return n
        }

        override fun getUri(): Uri? = uri

        override fun close() {
            if (uri != null) {
                uri = null
                transferEnded()
            }
        }
    }
}
