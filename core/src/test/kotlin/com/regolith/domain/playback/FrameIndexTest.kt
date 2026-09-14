package com.regolith.domain.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FrameIndexTest {
    @Test fun `positions quantise into ten second buckets`() {
        val index = FrameIndex<String>()
        assertEquals(0L, index.bucketOf(0))
        assertEquals(0L, index.bucketOf(9_999))
        assertEquals(1L, index.bucketOf(10_000))
        assertEquals(15_000L, index.positionOf(1))
        assertEquals(0L, index.bucketOf(-5)) // a drag past the start
    }

    @Test fun `nearest prefers the exact bucket, then the closest loaded neighbour`() {
        val index = FrameIndex<String>(toleranceBuckets = 2)
        index.put(3, "c")
        index.put(6, "f")
        assertEquals("c", index.nearest(35_000))
        assertEquals("c", index.nearest(45_000)) // bucket 4: c is one away
        assertEquals("f", index.nearest(52_000)) // bucket 5: c and f both one away; earlier wins the tie
        assertNull(index.nearest(90_000))       // bucket 9: three away from f
    }

    @Test fun `a zero tolerance takes the bucket's own frame or nothing`() {
        val index = FrameIndex<String>(toleranceBuckets = 2)
        index.put(3, "c")
        assertEquals("c", index.nearest(35_000, tolerance = 0))
        // What a chapter tile does: bucket 4 has no frame of its own, and
        // borrowing bucket 3's would put the wrong picture under a clock
        // saying 0:45.
        assertNull(index.nearest(45_000, tolerance = 0))
    }

    @Test fun `forget drops the frames without recycling them`() {
        val evicted = mutableListOf<String>()
        val index = FrameIndex<String>(onEvict = { evicted += it })
        index.put(1, "a")
        index.forget()
        assertEquals(0, index.size)
        assertNull(index.nearest(15_000))
        // Still on screen, so still whole: the garbage collector takes them.
        assertEquals(emptyList<String>(), evicted)
    }

    @Test fun `least recently used frames are evicted and recycled`() {
        val evicted = mutableListOf<String>()
        val index = FrameIndex<String>(capacity = 2, onEvict = { evicted += it })
        index.put(1, "a")
        index.put(2, "b")
        index.nearest(15_000) // touch a
        index.put(3, "c")     // evicts b
        assertEquals(listOf("b"), evicted)
        assertEquals(2, index.size)
        assertEquals("a", index.nearest(15_000))
    }

    @Test fun `missing buckets around a position, nearest first, within the runtime`() {
        val index = FrameIndex<String>()
        index.put(5, "loaded")
        assertEquals(listOf(4L, 6L, 3L, 7L), index.missingAround(55_000, radius = 2, maxBucket = 100))
        assertEquals(listOf(0L, 1L, 2L), index.missingAround(0, radius = 2, maxBucket = 100))
        assertEquals(listOf(10L, 9L, 8L), index.missingAround(105_000, radius = 2, maxBucket = 10))
    }
}
