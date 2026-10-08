package com.regolith.domain.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What Save to phone says once a batch of pictures is in the gallery (P20). */
class PictureSavesTest {

    @Test
    fun `one picture saved says where it went`() {
        val m = PictureSaves.message(saved = 1, total = 1)!!
        assertEquals("Saved to your gallery, in Pictures › Regolith", m.text)
        assertFalse(m.failed)
    }

    @Test
    fun `a batch is counted`() {
        assertEquals("Saved 3 pictures to your gallery, in Pictures › Regolith", PictureSaves.message(3, 3)!!.text)
    }

    @Test
    fun `a batch that partly failed says how far it got, as a failure`() {
        val m = PictureSaves.message(saved = 2, total = 5)!!
        assertEquals("Saved 2 of 5 pictures to Pictures › Regolith · the rest couldn’t be fetched", m.text)
        assertTrue(m.failed)
    }

    @Test
    fun `nothing saved blames the share, not the phone`() {
        assertEquals("Couldn’t save the picture: the share didn’t hand it over", PictureSaves.message(0, 1)!!.text)
        assertEquals("Couldn’t save the pictures: the share didn’t hand them over", PictureSaves.message(0, 4)!!.text)
    }

    @Test
    fun `nothing asked for says nothing`() {
        assertNull(PictureSaves.message(0, 0))
    }
}
