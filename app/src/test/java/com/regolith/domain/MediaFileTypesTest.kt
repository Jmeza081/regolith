package com.regolith.domain

import com.regolith.domain.media.MediaFileTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaFileTypesTest {
    @Test fun `video extensions are case-insensitive`() {
        assertTrue(MediaFileTypes.isVideo("Arrival.2016.2160p.MKV"))
        assertTrue(MediaFileTypes.isVideo("GH010423.mp4"))
    }

    @Test fun `sidecars and unknowns are not video`() {
        assertFalse(MediaFileTypes.isVideo("poster.jpg"))
        assertFalse(MediaFileTypes.isVideo("README"))
    }

    @Test fun `extension of dotless name is empty`() = assertEquals("", MediaFileTypes.extensionOf("Makefile"))
}
