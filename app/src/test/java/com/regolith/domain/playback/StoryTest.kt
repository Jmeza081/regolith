package com.regolith.domain.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** A collection's pictures played as a story (P20): its bar, its pace and its order. */
class StoryTest {

    @Test
    fun `a short story shows every segment`() {
        assertEquals(0 until 12, Story.window(count = 12, index = 4))
        assertEquals(0 until 20, Story.window(count = 20, index = 19))
    }

    @Test
    fun `a long story shows the twenty around the picture up, sliding along`() {
        assertEquals(0 until 20, Story.window(count = 300, index = 3))
        assertEquals(114 until 134, Story.window(count = 300, index = 124))
        assertEquals(280 until 300, Story.window(count = 300, index = 299))
    }

    @Test
    fun `the window always holds the picture up`() {
        for (index in 0 until 57) assert(index in Story.window(count = 57, index = index)) { "picture $index" }
    }

    @Test
    fun `a picture stays the pace, and a longer GIF plays through once`() {
        assertEquals(5_000L, Story.durationMs(StoryPace.FIVE, gifMs = null))
        assertEquals(5_000L, Story.durationMs(StoryPace.FIVE, gifMs = 3_010L))
        assertEquals(8_400L, Story.durationMs(StoryPace.FIVE, gifMs = 8_400L))
        assertEquals(3_000L, Story.durationMs(StoryPace.THREE, gifMs = null))
    }

    @Test
    fun `the length is the pictures at the pace`() {
        assertEquals(430_000L, Story.lengthMs(86, StoryPace.FIVE))
        assertEquals("7 min", Reel.roughLength(Story.lengthMs(86, StoryPace.FIVE)))
        assertEquals("36 s", Reel.roughLength(Story.lengthMs(12, StoryPace.THREE)))
    }

    @Test
    fun `the pace is read back by name, five seconds when it is unknown`() {
        assertEquals(StoryPace.EIGHT, StoryPace.of("EIGHT"))
        assertEquals(StoryPace.FIVE, StoryPace.of(null))
        assertEquals(StoryPace.FIVE, StoryPace.of("TEN"))
    }

    @Test
    fun `no seed plays the order as it is, a seed shuffles it the same way every time`() {
        val ids = (1L..30L).toList()
        assertEquals(ids, Story.order(ids, seed = null))
        val shuffled = Story.order(ids, seed = 42L)
        assertEquals(shuffled, Story.order(ids, seed = 42L))
        assertNotEquals(ids, shuffled)
        assertEquals(ids.toSet(), shuffled.toSet())
    }

    @Test
    fun `a story played from a picture starts on it, or at the start when it has gone`() {
        val order = listOf(5L, 9L, 2L)
        assertEquals(1, Story.startIndex(order, 9L))
        assertEquals(0, Story.startIndex(order, null))
        assertEquals(0, Story.startIndex(order, 77L))
    }
}
