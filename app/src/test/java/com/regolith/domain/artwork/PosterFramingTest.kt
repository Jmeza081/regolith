package com.regolith.domain.artwork

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PosterFramingTest {
    private val frame = Dimensions(1920f, 1080f)
    private val box = PosterFraming.boxIn(400f, 450f) // 300 x 450

    @Test
    fun `the box is the biggest 2 by 3 that fits`() {
        assertEquals(Dimensions(300f, 450f), box)
        assertEquals(Dimensions(200f, 300f), PosterFraming.boxIn(200f, 900f))
    }

    @Test
    fun `at rest the crop is the full height from the middle of the frame`() {
        val crop = PosterFraming().cropRect(frame, box)
        assertEquals(CropRect(left = 600, top = 0, width = 720, height = 1080), crop)
    }

    @Test
    fun `panning is stopped at the frame's edge`() {
        val far = PosterFraming(panX = 50f).clamped(frame, box)
        assertEquals(0, far.cropRect(frame, box).left)
        val farRight = PosterFraming(panX = -50f).clamped(frame, box)
        assertEquals(1920 - 720, farRight.cropRect(frame, box).left)
        // At zoom 1 the picture is exactly as tall as the box: no vertical travel.
        assertEquals(0f, PosterFraming(panY = 1f).clamped(frame, box).panY)
    }

    @Test
    fun `zoom never goes below cover or above the maximum`() {
        assertEquals(1f, PosterFraming().gesture(frame, box, 0f, 0f, 0f, 0f, zoomBy = 0.2f).zoom)
        assertEquals(PosterFraming.MAX_ZOOM, PosterFraming().gesture(frame, box, 0f, 0f, 0f, 0f, zoomBy = 100f).zoom)
    }

    @Test
    fun `pinching at the box centre zooms into the middle`() {
        val crop = PosterFraming().gesture(frame, box, 0f, 0f, 0f, 0f, zoomBy = 2f).cropRect(frame, box)
        assertEquals(CropRect(left = 780, top = 270, width = 360, height = 540), crop)
    }

    @Test
    fun `the point under the fingers stays under the fingers`() {
        val start = PosterFraming(zoom = 2f)
        // A source point 60 screen px right of the box centre...
        val s0 = start.scale(frame, box)
        val sourceX = frame.width / 2 + (60f - start.offsetX(box)) / s0
        val after = start.gesture(frame, box, centroidX = 60f, centroidY = 0f, dx = 0f, dy = 0f, zoomBy = 1.5f)
        val s1 = after.scale(frame, box)
        val screenX = after.offsetX(box) + (sourceX - frame.width / 2) * s1
        assertEquals(60f, screenX, 0.01f)
    }

    @Test
    fun `output is scaled down to fit, never up`() {
        assertEquals(1000 to 1500, PosterFraming.outputSize(CropRect(0, 0, 1440, 2160)))
        assertEquals(360 to 540, PosterFraming.outputSize(CropRect(0, 0, 360, 540)))
    }

    @Test
    fun `a portrait frame fits the box by width`() {
        val phone = Dimensions(1080f, 1920f)
        val crop = PosterFraming().cropRect(phone, box)
        assertEquals(1080, crop.width)
        assertEquals(1620, crop.height)
        assertTrue(crop.top in 149..151)
    }
}
