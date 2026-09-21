package com.regolith.domain.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The one sentence the nav chrome gets to say about background work. */
class BackgroundWorkTest {
    @Test
    fun `nothing running says nothing`() {
        assertNull(backgroundWork(scan = null, artwork = null))
    }

    @Test
    fun `a scan sweeps, because a walk has no total`() {
        val work = backgroundWork(ScanTally(shares = 1, files = 1204), artwork = null)!!
        assertEquals("Reading the share · 1,204 files", work.line)
        assertNull("no fraction: the share's size is only known once it has been walked", work.fraction)
    }

    @Test
    fun `a walk that has found nothing yet does not print a zero`() {
        assertEquals("Reading the share", backgroundWork(ScanTally(shares = 1, files = 0), artwork = null)!!.line)
    }

    @Test
    fun `two shares are counted, and their files summed by the caller`() {
        assertEquals("Reading 2 shares · 40 files", backgroundWork(ScanTally(shares = 2, files = 40), artwork = null)!!.line)
    }

    @Test
    fun `artwork reports a real fraction`() {
        val work = backgroundWork(scan = null, artwork = ArtworkTally(done = 340, total = 1360))!!
        assertEquals("Preparing artwork · 340 of 1,360", work.line)
        assertEquals(0.25f, work.fraction!!, 0.0001f)
    }

    @Test
    fun `artwork that has not counted its work yet sweeps`() {
        val work = backgroundWork(scan = null, artwork = ArtworkTally(done = 0, total = 0))!!
        assertEquals("Preparing artwork", work.line)
        assertNull(work.fraction)
    }

    @Test
    fun `a scan outranks an artwork pass`() {
        val work = backgroundWork(ScanTally(shares = 1, files = 12), ArtworkTally(done = 3, total = 9))!!
        assertEquals("the scan is the one changing what is IN the library", "Reading the share · 12 files", work.line)
    }
}
