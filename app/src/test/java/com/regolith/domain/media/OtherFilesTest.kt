package com.regolith.domain.media

import com.regolith.domain.media.OtherFiles.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What Browse calls the files in a folder that are not videos, and which it lists at all. */
class OtherFilesTest {

    @Test
    fun `a folder's poster is a picture, whatever the case of its extension`() {
        assertEquals(Kind.PICTURE, OtherFiles.kindOf("poster.jpg"))
        assertEquals(Kind.PICTURE, OtherFiles.kindOf("Folder.PNG"))
        assertEquals(Kind.PICTURE, OtherFiles.kindOf("poster.gif"))
    }

    @Test
    fun `subtitles are known by their extension, language tags and all`() {
        assertEquals(Kind.SUBTITLES, OtherFiles.kindOf("beach.en.srt"))
        assertEquals(Kind.SUBTITLES, OtherFiles.kindOf("beach.forced.vtt"))
        assertEquals(Kind.SUBTITLES, OtherFiles.kindOf("beach.ass"))
    }

    @Test
    fun `a chapters file is chapters, not a text file`() {
        assertEquals(Kind.CHAPTERS, OtherFiles.kindOf("beach.chapters.txt"))
        assertEquals(Kind.INFO, OtherFiles.kindOf("notes.txt"))
        assertEquals(Kind.INFO, OtherFiles.kindOf("beach.nfo"))
    }

    @Test
    fun `anything else is a file`() {
        assertEquals(Kind.OTHER, OtherFiles.kindOf("backup.zip"))
        assertEquals(Kind.OTHER, OtherFiles.kindOf("README"))
    }

    @Test
    fun `system files and files still being written are not listed`() {
        assertFalse(OtherFiles.isListed(".DS_Store"))
        assertFalse(OtherFiles.isListed("._poster.jpg"))
        assertFalse(OtherFiles.isListed("poster.jpg.part"))
        assertFalse(OtherFiles.isListed("beach.en.srt.PART"))
        assertTrue(OtherFiles.isListed("poster.jpg"))
        // Only the ending counts: a name with "part" in it is a name.
        assertTrue(OtherFiles.isListed("part one.nfo"))
    }
}
