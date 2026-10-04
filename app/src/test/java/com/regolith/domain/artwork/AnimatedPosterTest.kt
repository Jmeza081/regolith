package com.regolith.domain.artwork

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which folder posters move, what counts as a GIF, and what becomes of a GIF
 * picked on the phone as a folder's poster.
 */
class AnimatedPosterTest {

    @Test
    fun `only a top-level folder's poster moves`() {
        assertTrue("a collection on the Library's first screen", AnimatedPoster.allowedFor("Films"))
        assertFalse("the share root itself", AnimatedPoster.allowedFor(""))
        assertFalse("a folder inside a collection", AnimatedPoster.allowedFor("Series/Severance"))
        assertFalse(AnimatedPoster.allowedFor("Series/Severance/Season 1"))
    }

    @Test
    fun `a gif is told by its signature, not its name`() {
        assertTrue(AnimatedPoster.isGif("GIF89a…".toByteArray()))
        assertTrue(AnimatedPoster.isGif("GIF87a".toByteArray()))
        assertFalse("a JPEG renamed .gif", AnimatedPoster.isGif(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0, 0x10)))
        assertFalse(AnimatedPoster.isGif("GIF8".toByteArray()))
        assertFalse(AnimatedPoster.isGif(ByteArray(0)))
    }

    @Test
    fun `a gif picked for a collection goes up as it is, and moves`() {
        assertEquals(PickedPoster.ANIMATED, AnimatedPoster.forPicked("image/gif", 2_000_000, "Films"))
        assertEquals("poster.gif", PickedPoster.ANIMATED.fileName)
        assertEquals("a size the phone does not give is checked as it is read", PickedPoster.ANIMATED, AnimatedPoster.forPicked("image/gif", null, "Films"))
    }

    @Test
    fun `anything else picked is a still poster jpg`() {
        assertEquals(PickedPoster.STILL, AnimatedPoster.forPicked("image/jpeg", 2_000_000, "Films"))
        assertEquals(PickedPoster.STILL, AnimatedPoster.forPicked(null, null, "Films"))
        assertEquals(PickedPoster.STILL_NESTED, AnimatedPoster.forPicked("image/gif", 2_000_000, "Series/Severance"))
        assertEquals(PickedPoster.STILL_TOO_BIG, AnimatedPoster.forPicked("image/gif", ArtworkCandidates.MAX_IMAGE_BYTES + 1, "Films"))
        assertEquals(PickedPoster.ANIMATED, AnimatedPoster.forPicked("image/gif", ArtworkCandidates.MAX_IMAGE_BYTES, "Films"))
        for (still in listOf(PickedPoster.STILL, PickedPoster.STILL_NESTED, PickedPoster.STILL_TOO_BIG)) {
            assertEquals("poster.jpg", still.fileName)
        }
    }
}
