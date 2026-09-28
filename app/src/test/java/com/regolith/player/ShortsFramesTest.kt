package com.regolith.player

import android.graphics.Bitmap
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.regolith.data.artwork.FrameGrabber
import com.regolith.data.artwork.GrabbedFrame
import com.regolith.data.prefs.AppPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.util.Collections

/**
 * The Shorts strips made ahead of time: what lands on disk, and which clip
 * is made first when the panel and the next deck both want one.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class ShortsFramesTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    /** Every clip this grabber was asked for, in order. */
    private val asked = Collections.synchronizedList(mutableListOf<Long>())

    /** Clips whose every position comes back as ONE bitmap, the way Media3 dedupes seeks to one key frame. */
    private val oneBitmapFor = mutableSetOf<Long>()

    /** Clips whose grab fails outright. */
    private val failFor = mutableSetOf<Long>()

    /** The shared bitmap handed out for [oneBitmapFor], to check what became of it. */
    private var shared: Bitmap? = null

    /**
     * Set when the shared bitmap was already recycled by the time it was
     * handed over again. On a phone that is the "Can't compress a recycled
     * bitmap" crash; Robolectric's bitmaps do not enforce it, so it is
     * checked here instead.
     */
    @Volatile private var recycledTooSoon = false

    private val grabber = object : FrameGrabber {
        override suspend fun frameAt(fileId: Long, positionMs: Long, maxWidth: Int, maxHeight: Int): GrabbedFrame? = null

        override fun framesAt(fileId: Long, positionsMs: List<Long>, maxWidth: Int, maxHeight: Int): Flow<GrabbedFrame?> = flow {
            asked += fileId
            if (fileId in failFor) throw IllegalStateException("the share went away mid-clip")
            val one = Bitmap.createBitmap(18, 32, Bitmap.Config.ARGB_8888).also { shared = it }
            for (at in positionsMs) {
                if (fileId in oneBitmapFor && one.isRecycled) recycledTooSoon = true
                emit(GrabbedFrame(if (fileId in oneBitmapFor) one else Bitmap.createBitmap(18, 32, Bitmap.Config.ARGB_8888), at))
            }
        }
    }

    private fun frames(): ShortsFrames {
        File(context.cacheDir, "shorts-frames").deleteRecursively()
        val store = PreferenceDataStoreFactory.create { File(context.filesDir, "test-${System.nanoTime()}.preferences_pb") }
        return ShortsFrames(context, grabber, AppPreferences(store))
    }

    private fun clip(id: Long, modifiedAtMs: Long = 1_000) = ShortsClip(fileId = id, sizeBytes = 42_000_000, modifiedAtMs = modifiedAtMs, durationMs = 18_000)

    private fun ShortsFrames.awaitComplete(vararg clips: ShortsClip) = runBlocking {
        withTimeout(10_000) {
            updates.first { clips.all { c -> files(c).all { it != null } } }
        }
    }

    @Test
    fun `a clip warmed ahead has all eight frames on disk`() {
        val frames = frames()
        val clip = clip(7)
        assertTrue("nothing yet", frames.files(clip).all { it == null })
        frames.warmAhead(listOf(clip))
        frames.awaitComplete(clip)
        assertEquals(ShortsFrames.STRIP_FRAMES, frames.files(clip).count { it != null })
    }

    @Test
    fun `a clip replaced on the share is a different strip`() {
        val frames = frames()
        frames.warmAhead(listOf(clip(7)))
        frames.awaitComplete(clip(7))
        assertTrue("same id, new version: nothing made for it yet", frames.files(clip(7, modifiedAtMs = 2_000)).all { it == null })
    }

    @Test
    fun `what the panel shows is made before the next deck`() {
        val frames = frames()
        frames.warmNow(listOf(clip(1), clip(2)))
        frames.warmAhead(listOf(clip(10), clip(11)))
        frames.awaitComplete(clip(1), clip(2), clip(10), clip(11))
        assertEquals(listOf(1L, 2L), asked.take(2))
    }

    @Test
    fun `one bitmap handed over for every position is written for each of them, then let go`() {
        // What crashed the app on open: the frame was recycled after the
        // first position, and the second tried to write a recycled bitmap.
        val frames = frames()
        oneBitmapFor += 8L
        frames.warmAhead(listOf(clip(8)))
        frames.awaitComplete(clip(8))
        assertEquals(ShortsFrames.STRIP_FRAMES, frames.files(clip(8)).count { it != null })
        assertFalse("recycled while the grabber could still hand it over", recycledTooSoon)
        runBlocking { withTimeout(5_000) { while (shared?.isRecycled != true) kotlinx.coroutines.delay(20) } }
    }

    @Test
    fun `a clip that fails does not stop the next one, or the app`() {
        val frames = frames()
        failFor += 99L
        frames.warmAhead(listOf(clip(99), clip(100)))
        frames.awaitComplete(clip(100))
        assertEquals(listOf(99L, 100L), asked.toList())
        assertTrue(frames.files(clip(99)).all { it == null })
    }

    @Test
    fun `a clip already made is not made again`() {
        val frames = frames()
        frames.warmNow(listOf(clip(5)))
        frames.awaitComplete(clip(5))
        frames.warmNow(listOf(clip(5), clip(6)))
        frames.awaitComplete(clip(6))
        assertEquals(listOf(5L, 6L), asked.toList())
    }
}
