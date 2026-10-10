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

    /** A [width] x [height] grid of a bright picture, with [paint] darkening some of it. */
    private fun grid(width: Int = 64, height: Int = 36, paint: (x: Int, y: Int) -> Int? = { _, _ -> null }) =
        IntArray(width * height) { i -> paint(i % width, i / width) ?: random.nextInt(60, 220) }

    @Test fun `bars above and below a widescreen picture are found`() {
        val box = FrameLook.picture(grid { _, y -> if (y < 5 || y >= 31) 3 else null }, 64, 36)
        assertTrue(box != null && box.toList() == listOf(0, 5, 64, 31))
    }

    @Test fun `bars either side of a 4-by-3 picture are found`() {
        val box = FrameLook.picture(grid { x, _ -> if (x < 8 || x >= 56) 2 else null }, 64, 36)
        assertTrue(box != null && box.toList() == listOf(8, 0, 56, 36))
    }

    @Test fun `a dark sky over a picture is not a bar, nor is a dark frame, nor none`() {
        assertTrue("one band only", FrameLook.picture(grid { _, y -> if (y < 8) 3 else null }, 64, 36) == null)
        assertTrue("all dark", FrameLook.picture(IntArray(64 * 36) { 4 }, 64, 36) == null)
        assertTrue("no bars", FrameLook.picture(grid(), 64, 36) == null)
    }

    @Test fun `luma weighs green most and blue least`() {
        assertTrue(FrameLook.luma(0, 255, 0) > FrameLook.luma(255, 0, 0))
        assertTrue(FrameLook.luma(255, 0, 0) > FrameLook.luma(0, 0, 255))
        assertTrue(FrameLook.luma(255, 255, 255) in 254..255)
    }
}
