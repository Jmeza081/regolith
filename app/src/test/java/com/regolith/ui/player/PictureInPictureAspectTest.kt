package com.regolith.ui.player

import com.regolith.domain.playback.VideoInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The floating window's shape ([pipAspect]): the film's own, held inside
 * the 2.39:1 either way that Android allows: past that, setting the
 * window up throws rather than clamping.
 */
class PictureInPictureAspectTest {

    private fun video(width: Int, height: Int) =
        VideoInfo(width = width, height = height, videoMimeType = null, hdr = false, audioMimeType = null, audioChannels = null)

    @Test
    fun `a film's window takes the film's shape`() {
        assertEquals(1920 to 1080, pipAspect(video(1920, 1080)))
        assertEquals(1080 to 1920, pipAspect(video(1080, 1920)))
    }

    @Test
    fun `a CinemaScope film, just inside the limit, keeps its own shape`() {
        assertEquals(2048 to 858, pipAspect(video(2048, 858)))
    }

    @Test
    fun `wider than Android allows is held at the widest it does`() {
        assertEquals(239 to 100, pipAspect(video(3840, 1200)))
    }

    @Test
    fun `taller than Android allows is held at the tallest it does`() {
        assertEquals(100 to 239, pipAspect(video(400, 1200)))
    }

    @Test
    fun `no shape until the film's size is known`() {
        assertNull(pipAspect(null))
        assertNull(pipAspect(video(0, 0)))
    }
}
