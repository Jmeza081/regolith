package com.regolith.domain.playback

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * How [Reel] cuts a collection's moments into clips: ten seconds of each,
 * shorter where the next mark in the same video or the video's end comes
 * first, and never a flash.
 */
class ReelTest {

    private fun moment(fileId: Long, startMs: Long, name: String = "m$startMs") = ReelMoment(fileId, startMs, name, "video $fileId")

    @Test
    fun `each moment plays ten seconds`() {
        val clips = Reel.clips(listOf(moment(1, 60_000), moment(2, 5_000)), mapOf(1L to 600_000L, 2L to 600_000L))
        assertEquals(listOf(60_000L to 70_000L, 5_000L to 15_000L), clips.map { it.startMs to it.endMs })
    }

    @Test
    fun `a clip stops where the next mark in its video starts`() {
        // The backflip is four seconds after the jump: the jump's clip ends there.
        val clips = Reel.clips(listOf(moment(1, 100_000), moment(1, 104_000)), mapOf(1L to 600_000L))
        assertEquals(104_000L, clips[0].endMs)
        assertEquals(114_000L, clips[1].endMs)
    }

    @Test
    fun `the next mark counts in play order, whatever order the reel is in`() {
        // Sorted by name, the later mark comes first in the reel; the earlier one still stops at it.
        val clips = Reel.clips(listOf(moment(1, 104_000, "a"), moment(1, 100_000, "b")), mapOf(1L to 600_000L))
        assertEquals(114_000L, clips[0].endMs)
        assertEquals(104_000L, clips[1].endMs)
    }

    @Test
    fun `a mark in another video does not cut a clip short`() {
        val clips = Reel.clips(listOf(moment(1, 100_000), moment(2, 102_000)), mapOf(1L to 600_000L, 2L to 600_000L))
        assertEquals(110_000L, clips[0].endMs)
    }

    @Test
    fun `two marks a moment apart still play a second`() {
        val clips = Reel.clips(listOf(moment(1, 100_000), moment(1, 100_200)), mapOf(1L to 600_000L))
        assertEquals(101_000L, clips[0].endMs)
    }

    @Test
    fun `a clip stops at the end of its video`() {
        val clips = Reel.clips(listOf(moment(1, 595_000)), mapOf(1L to 600_000L))
        assertEquals(600_000L, clips[0].endMs)
    }

    @Test
    fun `a video whose length is not known yet sets no limit`() {
        val clips = Reel.clips(listOf(moment(1, 595_000)), mapOf(1L to null))
        assertEquals(605_000L, clips[0].endMs)
    }

    @Test
    fun `a mark at or past its video's end is left out`() {
        val clips = Reel.clips(listOf(moment(1, 600_000), moment(1, 10_000)), mapOf(1L to 600_000L))
        assertEquals(listOf(10_000L), clips.map { it.startMs })
    }

    @Test
    fun `a reel's length is its clips added up, said roughly`() {
        val clips = Reel.clips(List(6) { moment(it.toLong(), 0) }, emptyMap())
        assertEquals(60_000L, Reel.lengthMs(clips))
        assertEquals("1 min", Reel.roughLength(Reel.lengthMs(clips)))
        assertEquals("45 s", Reel.roughLength(45_000))
        assertEquals("1 s", Reel.roughLength(300))
        assertEquals("3 min", Reel.roughLength(170_000))
    }
}
