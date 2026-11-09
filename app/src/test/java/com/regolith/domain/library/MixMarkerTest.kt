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
