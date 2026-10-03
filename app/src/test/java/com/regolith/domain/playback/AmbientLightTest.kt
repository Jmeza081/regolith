package com.regolith.domain.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AmbientLightTest {

    @Test
    fun `a saved choice reads back by name`() {
        AmbientLight.entries.forEach { assertEquals(it, AmbientLight.of(it.name)) }
    }

    @Test
    fun `a phone that never chose is on Mirror`() {
        assertEquals(AmbientLight.MIRROR, AmbientLight.of(null, legacyOn = null))
    }

    @Test
    fun `the old switch carries over - off stays off, on is Mirror`() {
        assertEquals(AmbientLight.OFF, AmbientLight.of(null, legacyOn = false))
        assertEquals(AmbientLight.MIRROR, AmbientLight.of(null, legacyOn = true))
    }

    @Test
    fun `a choice by name outranks the old switch`() {
        assertEquals(AmbientLight.COLOR_BLEED, AmbientLight.of("COLOR_BLEED", legacyOn = false))
    }

    @Test
    fun `an unknown name falls back like no name`() {
        assertEquals(AmbientLight.OFF, AmbientLight.of("NEON", legacyOn = false))
        assertEquals(AmbientLight.MIRROR, AmbientLight.of("NEON"))
    }

    @Test
    fun `only the live lights need to read the picture back`() {
        assertFalse(AmbientLight.OFF.live)
        assertTrue(AmbientLight.MIRROR.live)
        assertTrue(AmbientLight.COLOR_BLEED.live)
    }
}
