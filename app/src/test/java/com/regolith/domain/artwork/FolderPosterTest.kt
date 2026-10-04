package com.regolith.domain.artwork

import com.regolith.domain.smb.SmbEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A picture from the phone made into a folder's poster (P19): what counts as
 * the folder's artwork already, what kept pictures are renamed to, and the
 * size the new one is saved at.
 */
class FolderPosterTest {

    private fun file(name: String, size: Long = 1_000) = SmbEntry(name, isDirectory = false, sizeBytes = size, modifiedAtMs = 0)
    private fun dir(name: String) = SmbEntry(name, isDirectory = true, sizeBytes = 0, modifiedAtMs = 0)

    @Test
    fun `the new picture is called poster jpg, the first name Regolith looks for`() {
        assertEquals("poster.jpg", FolderPoster.NAME)
        assertEquals("poster", ArtworkCandidates.sidecarStems.first())
    }

    @Test
    fun `a folder's own pictures are its artwork, whatever their case or size`() {
        val entries = listOf(
            file("Heat.1995.1080p.mkv"),
            file("FOLDER.JPG"),
            file("cover.webp", size = 40L * 1024 * 1024),
            file("Heat.1995.1080p.jpg"),
            file("IMG_2041.png"),
            file("poster.txt"),
            dir("poster.jpg"),
        )
        assertEquals(listOf("FOLDER.JPG", "cover.webp"), FolderPoster.existing(entries).map { it.name })
    }

    @Test
    fun `they come in the order Regolith ranks them, so the first is the one it shows`() {
        val entries = listOf(file("thumb.jpg"), file("folder.png"), file("poster.png"), file("folder.jpg"))
        assertEquals(listOf("poster.png", "folder.jpg", "folder.png", "thumb.jpg"), FolderPoster.existing(entries).map { it.name })
    }

    @Test
    fun `a kept picture is numbered the way a kept upload is, and stops counting as artwork`() {
        val kept = FolderPoster.keptNames(listOf("folder.jpg"), listOf("folder.jpg", "Heat.1995.mkv"))
        assertEquals(mapOf("folder.jpg" to "folder (1).jpg"), kept)
        assertFalse(ArtworkCandidates.isSidecarName("folder (1).jpg"))
    }

    @Test
    fun `an old poster jpg is renamed too, out of the new one's way`() {
        assertEquals(mapOf("poster.jpg" to "poster (1).jpg"), FolderPoster.keptNames(listOf("poster.jpg"), listOf("poster.jpg")))
    }

    @Test
    fun `kept names skip what the folder already has, and never collide with each other`() {
        val taken = listOf("folder.jpg", "folder (1).jpg", "FOLDER.png")
        val kept = FolderPoster.keptNames(listOf("folder.jpg", "FOLDER.png"), taken)
        assertEquals("folder (2).jpg", kept["folder.jpg"])
        assertEquals("FOLDER (1).png", kept["FOLDER.png"])
        assertEquals(kept.size, kept.values.map { it.lowercase() }.toSet().size)
        assertTrue(kept.values.none { it.equals(FolderPoster.NAME, ignoreCase = true) })
    }

    @Test
    fun `a big picture is brought down to the long side, keeping its shape`() {
        assertEquals(1500 to 2000, FolderPoster.targetSize(3000, 4000))
        assertEquals(2000 to 1125, FolderPoster.targetSize(4032, 2268))
    }

    @Test
    fun `a small picture is never enlarged`() {
        assertEquals(600 to 900, FolderPoster.targetSize(600, 900))
        assertEquals(2000 to 3, FolderPoster.targetSize(2000, 3))
    }

    @Test
    fun `a picture far longer than it is wide still keeps a pixel across`() {
        assertEquals(1 to 2000, FolderPoster.targetSize(1, 9000))
    }

    @Test
    fun `a gif already there counts as the folder's artwork, after the formats ranked above it`() {
        val entries = listOf(file("poster.gif"), file("folder.png"), file("poster.jpg"))
        assertEquals(listOf("poster.jpg", "poster.gif", "folder.png"), FolderPoster.existing(entries).map { it.name })
    }

    @Test
    fun `a new poster gif moves an old poster jpg out of its way, since the jpeg would outrank it`() {
        val kept = FolderPoster.keptNames(listOf("poster.jpg"), taken = listOf("poster.jpg", "poster (1).jpg"), name = AnimatedPoster.NAME)
        assertEquals(mapOf("poster.jpg" to "poster (2).jpg"), kept)
    }
}
