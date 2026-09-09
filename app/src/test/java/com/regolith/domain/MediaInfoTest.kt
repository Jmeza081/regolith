package com.regolith.domain

import com.regolith.domain.media.MediaInfo
import com.regolith.domain.playback.VideoInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class MediaInfoTest {
    private val info = MediaInfo(
        durationMs = 6_540_000, width = 1920, height = 1080, frameRate = 23.976f, videoMimeType = "video/avc", hdr = false,
        audioMimeType = "audio/ac3", audioChannels = 6, audioSampleRate = 48_000,
    )

    @Test fun `title detail lines read like the design`() {
        assertEquals("H.264 · 1920×1080 · 23.98 fps", info.videoLine)
        assertEquals("AC-3 · 5.1 · 48 kHz", info.audioLine)
        assertEquals("24 fps", MediaInfo.formatFps(24f))
    }

    @Test fun `unknown parts are dropped, not printed as zero`() {
        val bare = info.copy(width = null, height = null, frameRate = null, audioChannels = null, audioSampleRate = null)
        assertEquals("H.264", bare.videoLine)
        assertEquals("AC-3", bare.audioLine)
    }

    @Test fun `resolution chip from stored size`() {
        assertEquals("4K", VideoInfo.resolutionLabelFor(3840, 2160))
        assertEquals("1080p", VideoInfo.resolutionLabelFor(1920, 800)) // scope crop still reads as 1080p
        assertEquals("", VideoInfo.resolutionLabelFor(null, null))
    }
}
