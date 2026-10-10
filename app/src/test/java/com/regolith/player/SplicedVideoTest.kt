package com.regolith.player

import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.mkv.MatroskaExtractor
import androidx.media3.extractor.mp4.FragmentedMp4Extractor
import androidx.media3.extractor.text.SubtitleParser
import androidx.media3.test.utils.FakeExtractorInput
import androidx.media3.test.utils.FakeExtractorOutput
import androidx.media3.test.utils.FakeTrackOutput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.regolith.domain.media.UnindexedVideo
import com.regolith.domain.smb.SplicedByteSource
import com.regolith.testing.FakeSmbGateway.ByteArraySource
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * A file with no index, spliced to start in its middle, through Media3's own
 * extractors: the ones the frame grab decodes it with. Each must read the
 * spliced file to its end, and its first picture must be a key frame at
 * (about) 0:00, or the frame grab waits, in real time, for the clock to reach it.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class SplicedVideoTest {
    private fun bytes(name: String) = requireNotNull(javaClass.classLoader?.getResourceAsStream("video/$name")) { name }.readBytes()

    private fun spliced(name: String): ByteArray {
        val video = ByteArraySource(bytes(name))
        val layout = UnindexedVideo.layout(video)!!
        val cut = UnindexedVideo.cutNear(video, layout, 0.5)!!
        val spliced = SplicedByteSource(video, layout.headerEnd, cut.offset, cut.prefix)
        val out = ByteArray(spliced.size.toInt())
        var done = 0
        while (done < out.size) {
            val n = spliced.readAt(done.toLong(), out, done, out.size - done)
            if (n <= 0) break
            done += n
        }
        return out
    }

    /** Every sample [extractor] finds in [data]: the video track's. */
    private fun video(extractor: Extractor, data: ByteArray): FakeTrackOutput {
        val output = FakeExtractorOutput()
        extractor.init(output)
        val input = FakeExtractorInput.Builder().setData(data).build()
        val position = PositionHolder()
        var result = Extractor.RESULT_CONTINUE
        while (result != Extractor.RESULT_END_OF_INPUT) {
            result = extractor.read(input, position)
            if (result == Extractor.RESULT_SEEK) input.setPosition(position.position.toInt())
        }
        return (0 until output.numberOfTracks).map { output.trackOutputs.valueAt(it) }
            .single { MimeTypes.isVideo(it.lastFormat?.sampleMimeType) }
    }

    private fun assertStartsOnAKeyFrameAtZero(track: FakeTrackOutput) {
        assertTrue("some pictures", track.sampleCount > 10)
        // Within a frame or two of it: an MP4 shows its first picture after the
        // reordering delay of its B-frames (80 ms here), spliced or not.
        assertTrue("first picture at 0:00, not ${track.getSampleTimeUs(0) / 1000}ms", track.getSampleTimeUs(0) in 0..200_000)
        assertTrue("first picture a key frame", track.getSampleFlags(0) and C.BUFFER_FLAG_KEY_FRAME != 0)
    }

    @Test fun `an mkv cut in the middle reads to its end and starts on a key frame at zero`() {
        assertStartsOnAKeyFrameAtZero(video(MatroskaExtractor(SubtitleParser.Factory.UNSUPPORTED), spliced("unindexed.mkv")))
    }

    @Test fun `a fragmented mp4 cut in the middle does too`() {
        assertStartsOnAKeyFrameAtZero(video(FragmentedMp4Extractor(SubtitleParser.Factory.UNSUPPORTED), spliced("fragmented.mp4")))
    }
}
