package com.regolith.domain.media

import com.regolith.domain.smb.SeekableByteSource
import com.regolith.domain.smb.SplicedByteSource
import com.regolith.testing.FakeSmbGateway.ByteArraySource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The container reader on real files: 20 seconds of picture and sound, a key
 * frame every second, made by ffmpeg the ways a recorder or a download makes
 * them (`src/test/resources/video`). The unindexed ones were written to a
 * pipe, so the muxer could not go back to write an index or a duration.
 */
class UnindexedVideoTest {
    private fun file(name: String): SeekableByteSource =
        ByteArraySource(requireNotNull(javaClass.classLoader?.getResourceAsStream("video/$name")) { name }.readBytes())

    @Test fun `an mkv with no cues gives its header, its runtime and a cut in its middle`() {
        val video = file("unindexed.mkv")
        val layout = UnindexedVideo.layout(video)!!
        assertEquals(UnindexedVideo.Kind.MATROSKA, layout.kind)
        assertEquals("ticks of a millisecond, Matroska's default", 1_000_000L, layout.timescale)
        assertEquals("the video is the first track", 1L, layout.videoTrack)

        val runtime = UnindexedVideo.runtimeMs(video, layout)
        assertTrue("runtime $runtime: the last cluster starts within its last few seconds", runtime != null && runtime in 14_000..20_200)

        val cut = UnindexedVideo.cutNear(video, layout, 0.5)!!
        assertTrue("cut at ${cut.timeMs}ms", cut.timeMs in 6_000..14_000)
        assertTrue("on a key frame, one a second", cut.timeMs % 1000 < 50)
        assertTrue(cut.offset > layout.headerEnd)
    }

    @Test fun `a fragmented mp4 with no sidx gives the same`() {
        val video = file("fragmented.mp4")
        val layout = UnindexedVideo.layout(video)!!
        assertEquals(UnindexedVideo.Kind.FRAGMENTED_MP4, layout.kind)
        assertTrue(layout.timescale > 0)
        assertNotNull(layout.videoTrack)

        val runtime = UnindexedVideo.runtimeMs(video, layout)
        assertTrue("runtime $runtime", runtime != null && runtime in 18_000..20_200)

        val cut = UnindexedVideo.cutNear(video, layout, 0.5)!!
        assertTrue("cut at ${cut.timeMs}ms", cut.timeMs in 8_000..12_000)
        assertTrue("on a key frame, one a second", cut.timeMs % 1000 < 50)
    }

    @Test fun `cuts move through the file in order, from the first second to the last`() {
        for (name in listOf("unindexed.mkv", "fragmented.mp4")) {
            val video = file(name)
            val layout = UnindexedVideo.layout(video)!!
            val times = listOf(0.0, 0.25, 0.5, 0.75, 1.0).map { UnindexedVideo.cutNear(video, layout, it)!!.timeMs }
            assertEquals("$name: $times", times.sorted(), times)
            assertTrue("$name: $times", times.first() < 2_000 && times.last() > 14_000)
        }
    }

    @Test fun `the header and a cut make a file that starts in the middle, with its clock at zero`() {
        for (name in listOf("unindexed.mkv", "fragmented.mp4")) {
            val video = file(name)
            val layout = UnindexedVideo.layout(video)!!
            val cut = UnindexedVideo.cutNear(video, layout, 0.5)!!
            val spliced = SplicedByteSource(video, layout.headerEnd, cut.offset, cut.prefix)
            assertEquals(layout.headerEnd + cut.prefix.size + video.size - cut.offset, spliced.size)
            // It reads as the same kind of file, with the same header, and its
            // first key frame is right after it, at 0:00 rather than half way.
            assertEquals(name, layout, UnindexedVideo.layout(spliced))
            val first = UnindexedVideo.cutNear(spliced, layout, 0.0)!!
            assertEquals(name, 0L, first.timeMs)
            assertTrue("$name: the real time is kept apart, ${cut.timeMs}ms", cut.timeMs > 5_000)
        }
    }

    @Test fun `a file anything can already seek in needs none of this`() {
        assertNull("an mkv with cues", UnindexedVideo.layout(file("indexed.mkv")))
        assertNull("a plain mp4", UnindexedVideo.layout(file("plain.mp4")))
        assertNull("not a video at all", UnindexedVideo.layout(ByteArraySource("hello, world".toByteArray())))
        assertNull("empty", UnindexedVideo.layout(ByteArraySource(ByteArray(0))))
    }
}
