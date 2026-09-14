package com.regolith.domain.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AbLoopTest {
    @Test fun `taps in either order make the same loop`() {
        assertEquals(AbLoop(10_000, 20_000), AbLoop.between(20_000, 10_000, 60_000))
    }

    @Test fun `two taps at the same spot yield the minimum span`() {
        assertEquals(AbLoop(10_000, 10_500), AbLoop.between(10_000, 10_000, 60_000))
    }

    @Test fun `nudges respect the span and the duration`() {
        val loop = AbLoop(10_000, 11_000)
        assertEquals(10_500, loop.nudgeA(+1_000).aMs)          // clamped to B - min span
        assertEquals(60_000, loop.nudgeB(+90_000, 60_000).bMs)  // clamped to duration
        assertEquals(0, loop.nudgeA(-20_000).aMs)
    }

    @Test fun `restart fires at B`() {
        val loop = AbLoop(1_000, 2_000)
        assertFalse(loop.shouldRestart(1_999))
        assertTrue(loop.shouldRestart(2_000))
    }
}

class SeekStackerTest {
    @Test fun `double tap seeks ten seconds and single taps stack inside the window`() {
        val s = SeekStacker()
        assertEquals(10_000L, s.onDoubleTap(+1, nowMs = 0))
        assertEquals(10_000L, s.onSingleTap(+1, nowMs = 300))
        assertEquals("+20s", s.pendingLabel(nowMs = 400))
        assertEquals(10_000L, s.onSingleTap(+1, nowMs = 600))
        assertEquals(30_000L, s.accumulatedMs)
    }

    @Test fun `a lone single tap is not a seek`() {
        assertNull(SeekStacker().onSingleTap(+1, nowMs = 0))
    }

    @Test fun `the window closes and direction changes reset the stack`() {
        val s = SeekStacker()
        s.onDoubleTap(+1, nowMs = 0)
        assertNull(s.onSingleTap(+1, nowMs = 2_000))
        assertEquals(-10_000L, s.onDoubleTap(-1, nowMs = 2_100))
        assertEquals(-10_000L, s.accumulatedMs)
        assertEquals("−10s", s.pendingLabel(nowMs = 2_200))
    }
}

class VideoInfoTest {
    @Test fun `labels match the design chips`() {
        val info = VideoInfo(3840, 2160, "video/hevc", hdr = true, audioMimeType = "audio/eac3", audioChannels = 6)
        assertEquals(listOf("4K", "HDR", "HEVC"), info.chips)
        assertEquals("E-AC-3 · 5.1", info.audioLabel)
        assertEquals("1080p", VideoInfo(1920, 800, "video/avc", false, null, null).resolutionLabel)
        assertEquals(listOf("720p", "H.264"), VideoInfo(1280, 720, "video/avc", false, null, null).chips)
    }
}
