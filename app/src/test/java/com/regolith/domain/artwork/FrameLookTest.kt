package com.regolith.domain.artwork

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Which frames say nothing about a video, judged from a grid of brightness. */
class FrameLookTest {
    private val random = Random(7)

    /** A picture: brightness all over the place. */
    private fun scene(dark: Int = 40, light: Int = 220) = IntArray(576) { random.nextInt(dark, light) }

    @Test fun `black is blank, and so is nearly black`() {
        assertTrue(FrameLook.blank(IntArray(576) { 0 }))
        assertTrue(FrameLook.blank(IntArray(576) { random.nextInt(0, 30) }))
    }

    @Test fun `one flat colour is blank, however bright`() {
        assertTrue("a grey title card", FrameLook.blank(IntArray(576) { 128 }))
        assertTrue("white", FrameLook.blank(IntArray(576) { 250 + random.nextInt(0, 3) }))
    }

    @Test fun `a small logo on black is blank`() {
        val logo = IntArray(576) { if (it in 280..300) 240 else 4 }
        assertTrue(FrameLook.blank(logo))
    }

    @Test fun `a picture is not, a dark one included`() {
        assertFalse(FrameLook.blank(scene()))
        assertFalse("a night scene", FrameLook.blank(scene(dark = 10, light = 90)))
    }

    @Test fun `luma weighs green most and blue least`() {
        assertTrue(FrameLook.luma(0, 255, 0) > FrameLook.luma(255, 0, 0))
        assertTrue(FrameLook.luma(255, 0, 0) > FrameLook.luma(0, 0, 255))
        assertTrue(FrameLook.luma(255, 255, 255) in 254..255)
    }
}
