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

    private val grabber = object : FrameGrabber {
        override suspend fun frameAt(fileId: Long, positionMs: Long, maxWidth: Int, maxHeight: Int): GrabbedFrame? = null

        override fun framesAt(fileId: Long, positionsMs: List<Long>, maxWidth: Int, maxHeight: Int): Flow<GrabbedFrame?> = flow {
            asked += fileId
            for (at in positionsMs) emit(GrabbedFrame(Bitmap.createBitmap(18, 32, Bitmap.Config.ARGB_8888), at))
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
    fun `a clip already made is not made again`() {
        val frames = frames()
        frames.warmNow(listOf(clip(5)))
        frames.awaitComplete(clip(5))
        frames.warmNow(listOf(clip(5), clip(6)))
        frames.awaitComplete(clip(6))
        assertEquals(listOf(5L, 6L), asked.toList())
    }
}
