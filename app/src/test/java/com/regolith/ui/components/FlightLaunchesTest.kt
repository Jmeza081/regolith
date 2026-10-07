package com.regolith.ui.components

import androidx.compose.ui.geometry.Rect
import com.regolith.domain.artwork.ArtworkOwner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [FlightLaunches]: which tap a page beside the wall may fly in by hand. A
 * page claims the tile tapped a moment ago if it shows the same picture, once;
 * a tap that nothing claims goes stale, so a page opened later some other way
 * slides in as it always has instead of waiting for a poster that never comes.
 */
class FlightLaunchesTest {

    private var clock = 0L
    private val launches = FlightLaunches(now = { clock })
    private val heat = ArtworkOwner.File(30)
    private val tile = Rect(100f, 1077f, 468f, 1733f)

    @Test
    fun `a page claims the tile tapped for it, with where the tile was`() {
        launches.launched(heat, tile, poster = null)
        clock += 16_000_000L
        val launch = launches.claim(heat)
        assertNotNull(launch)
        assertEquals(tile, launch!!.from)
    }

    @Test
    fun `a tap is claimed once`() {
        launches.launched(heat, tile, poster = null)
        assertNotNull(launches.claim(heat))
        assertNull(launches.claim(heat))
        assertFalse(launches.launching)
    }

    @Test
    fun `a page for another picture leaves the tap alone`() {
        launches.launched(heat, tile, poster = null)
        assertNull(launches.claim(ArtworkOwner.File(31)))
        assertTrue("still there for its own page", launches.launching)
        assertNotNull(launches.claim(heat))
    }

    @Test
    fun `a tap nothing claimed goes stale after half a second`() {
        launches.launched(heat, tile, poster = null)
        clock += 499_000_000L
        assertTrue(launches.launching)
        clock += 2_000_000L
        assertFalse(launches.launching)
        assertNull(launches.claim(heat))
    }

    @Test
    fun `the last tap is the one that counts`() {
        launches.launched(heat, tile, poster = null)
        launches.launched(ArtworkOwner.Folder(15), tile, poster = null)
        assertNull(launches.claim(heat))
    }
}
