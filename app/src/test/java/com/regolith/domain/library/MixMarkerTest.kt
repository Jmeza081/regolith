package com.regolith.domain.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MixMarkerTest {
    @Test fun `a folder holding the marker is a mix`() {
        assertTrue(MixMarker.foundIn(listOf("beach.mp4", ".regolith-mix", "luau.mp4")))
    }

    @Test fun `a folder without it is not, poster or no poster`() {
        assertFalse(MixMarker.foundIn(listOf("beach.mp4", "luau.mp4")))
        assertFalse(MixMarker.foundIn(listOf("poster.jpg", "concert.mp4")))
    }

    @Test fun `only the exact name counts`() {
        assertFalse(MixMarker.foundIn(listOf("regolith-mix", ".regolith-mix.txt", ".Regolith-Mix")))
    }

    @Test fun `the marker sits inside the folder, or at the root of the share`() {
        assertEquals("Assorted/.regolith-mix", MixMarker.pathIn("Assorted"))
        assertEquals("Videos/Assorted/.regolith-mix", MixMarker.pathIn("Videos/Assorted"))
        assertEquals(".regolith-mix", MixMarker.pathIn(""))
    }
}

class MixSectionsTest {
    // "a-" and "m-" mark the mixes; the list arrives already sorted.
    private val wall = listOf("Band", "a-Assorted", "Concert", "m-Misc", "clip.mp4")
    private fun isMix(s: String) = s.startsWith("a-") || s.startsWith("m-")

    @Test fun `All puts the collections first and the mixes after, each in the wall's order`() {
        val s = wall.byMix(MixFilter.ALL, ::isMix)
        assertEquals(listOf("Band", "Concert", "clip.mp4", "a-Assorted", "m-Misc"), s.items)
        assertEquals(3, s.mixStart)
        assertEquals(MixSplit(collections = 3, mixes = 2), s.split)
    }

    @Test fun `a filter shows one kind, with no heading, and keeps the counts for the chips`() {
        val c = wall.byMix(MixFilter.COLLECTIONS, ::isMix)
        assertEquals(listOf("Band", "Concert", "clip.mp4"), c.items)
        assertEquals(null, c.mixStart)
        val m = wall.byMix(MixFilter.MIXES, ::isMix)
        assertEquals(listOf("a-Assorted", "m-Misc"), m.items)
        assertEquals(MixSplit(3, 2), m.split)
    }

    @Test fun `a wall with no mixes is left as it is, whatever the filter`() {
        val plain = listOf("Band", "Concert")
        for (f in MixFilter.entries) {
            assertEquals(MixSections(plain, null, null), plain.byMix(f, ::isMix))
        }
    }

    @Test fun `a wall of nothing but mixes has nothing to choose between`() {
        val mixes = listOf("a-One", "m-Two")
        assertEquals(MixSections(mixes, null, null), mixes.byMix(MixFilter.COLLECTIONS, ::isMix))
    }
}
