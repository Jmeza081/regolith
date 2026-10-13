package com.regolith.domain.playback

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The chapter sheet opens only when every tile has a picture, and every way
 * of getting there ends in a picture. Time is the test scheduler's, so a
 * minute of waiting costs nothing.
 */
class ChapterWallLoaderTest {
    private val hour = 60 * 60_000L
    private val chapters = ChapterMarks.evenly(hour) // 12 parts
    private val times = ChapterStills.timesFor(chapters, hour, ChapterSource.EVEN)
    private val starts = chapters.map { it.startMs }

    private class FakePictures(
        var standIn: String? = "film",
        val onMake: suspend FakePictures.(List<Long>) -> Unit = { ts -> ts.forEach { land(it) } },
    ) : ChapterPictures<String> {
        val cache = mutableMapOf<Long, CachedStill<String>>()
        val makes = mutableListOf<List<Long>>()
        fun land(at: Long) { cache[at] = CachedStill.Picture("frame@$at") }
        override suspend fun cached(fileId: Long, timeMs: Long) = cache[timeMs]
        override suspend fun make(fileId: Long, timesMs: List<Long>) {
            makes += timesMs
            onMake(timesMs)
        }
        override suspend fun standIn(fileId: Long) = standIn
    }

    private suspend fun FakePictures.walls() = ChapterWallLoader(this).load(7, chapters, hour, ChapterSource.EVEN).toList()

    private fun ChapterWall<String>.own() = frames.values.count { it.startsWith("frame@") }

    @Test
    fun `a wall already on disk opens at once and asks for nothing`() = runTest {
        val pictures = FakePictures().apply { times.forEach { land(it) } }
        val walls = pictures.walls()
        assertEquals(1, walls.size)
        assertTrue(walls.single().complete)
        assertEquals(times.map { "frame@$it" }, starts.map { walls.single().frames[it] })
        assertTrue(pictures.makes.isEmpty())
    }

    @Test
    fun `the pill waits for every frame, however one by one they come`() = runTest {
        val pictures = FakePictures(onMake = { ts -> ts.forEach { delay(1_000); land(it) } })
        val walls = pictures.walls()
        val first = walls.first { it.complete }
        assertEquals(12, first.own())
        assertEquals(listOf(times), pictures.makes)
    }

    @Test
    fun `only the missing frames are made, all on one run`() = runTest {
        val pictures = FakePictures().apply { times.take(5).forEach { land(it) } }
        pictures.walls()
        assertEquals(listOf(times.drop(5)), pictures.makes)
    }

    @Test
    fun `a frame that cannot be had shows the film's picture`() = runTest {
        val pictures = FakePictures(onMake = { ts -> ts.forEach { if (it == times[3]) cache[it] = CachedStill.Nothing else land(it) } })
        val wall = pictures.walls().first { it.complete }
        assertEquals(11, wall.own())
        assertEquals("film", wall.frames[starts[3]])
    }

    @Test
    fun `a stalled run opens on stand-ins, and the frames take their places as they land`() = runTest {
        val pictures = FakePictures(onMake = { ts ->
            ts.take(3).forEach { land(it) }
            delay(40_000)
            ts.drop(3).forEach { land(it) }
        })
        val walls = pictures.walls()
        val opened = walls.first { it.complete }
        assertEquals(3, opened.own())
        assertEquals(12, opened.frames.size)
        assertEquals(12, walls.last().own())
    }

    @Test
    fun `a slow but steady run opens at the ceiling`() = runTest {
        val pictures = FakePictures(onMake = { ts -> ts.forEach { delay(10_000); land(it) } })
        val start = testScheduler.currentTime
        var openedAt = -1L
        ChapterWallLoader(pictures).load(7, chapters, hour, ChapterSource.EVEN).collect { wall ->
            if (wall.complete && openedAt < 0) openedAt = testScheduler.currentTime - start
        }
        assertTrue("opened at $openedAt", openedAt in ChapterWallLoader.CEILING_MS..ChapterWallLoader.CEILING_MS + ChapterWallLoader.POLL_MS)
    }

    @Test
    fun `frames the share was too unwell to try are asked for again`() = runTest {
        var runs = 0
        val pictures = FakePictures(onMake = { ts ->
            runs++
            ts.forEach { if (runs > 1 || it !in times.take(2)) land(it) }
        })
        val walls = pictures.walls()
        val opened = walls.first { it.complete }
        assertEquals("film", opened.frames[starts[0]])
        assertEquals(12, walls.last().own())
        assertEquals(listOf(times, times.take(2)), pictures.makes)
    }

    @Test
    fun `asking again stops, and the stand-in stays`() = runTest {
        val pictures = FakePictures(onMake = { ts -> ts.forEach { if (it != times[0]) land(it) } })
        val walls = pictures.walls()
        assertEquals(1 + ChapterWallLoader.RETRIES, pictures.makes.size)
        assertTrue(walls.last().complete)
        assertEquals("film", walls.last().frames[starts[0]])
    }

    @Test
    fun `a film with no picture anywhere still opens`() = runTest {
        val pictures = FakePictures(standIn = null, onMake = { ts -> ts.forEach { if (it == times[0]) cache[it] = CachedStill.Nothing else land(it) } })
        val wall = pictures.walls().last()
        assertTrue(wall.complete)
        assertEquals(11, wall.frames.size)
    }

    @Test
    fun `nothing to wait for without chapters`() = runTest {
        val walls = ChapterWallLoader(FakePictures()).load(7, emptyList(), 12_000L, ChapterSource.EVEN).toList()
        assertEquals(listOf(ChapterWall.withoutPictures<String>()), walls)
    }

    @Test
    fun `the first answer is a spinning pill, not an open sheet`() = runTest {
        val walls = FakePictures().walls()
        assertFalse(walls.first().complete)
    }
}
