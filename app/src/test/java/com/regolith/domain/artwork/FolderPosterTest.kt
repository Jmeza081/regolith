package com.regolith.domain.artwork

import com.regolith.domain.smb.SmbEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.util.Locale

/**
 * A picture made into a folder's poster (P19, P20): what counts as the
 * folder's artwork already, what kept pictures are renamed to, how a picture
 * goes whole, and the size the new one is saved at.
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

    private val day = LocalDate.of(2026, 10, 8)

    @Test
    fun `a kept picture takes the day in its name, and stops counting as artwork`() {
        val kept = FolderPoster.keptNames(listOf("folder.jpg"), listOf("folder.jpg", "Heat.1995.mkv"), day)
        assertEquals(mapOf("folder.jpg" to "folder (8 Oct).jpg"), kept)
        assertFalse(ArtworkCandidates.isSidecarName("folder (8 Oct).jpg"))
    }

    @Test
    fun `an old poster jpg is kept too, out of the new one's way`() {
        assertEquals(mapOf("poster.jpg" to "poster (8 Oct).jpg"), FolderPoster.keptNames(listOf("poster.jpg"), listOf("poster.jpg"), day))
    }

    @Test
    fun `kept names skip what the folder already has, and never collide with each other`() {
        val taken = listOf("folder.jpg", "folder (8 Oct).jpg", "FOLDER.png")
        val kept = FolderPoster.keptNames(listOf("folder.jpg", "FOLDER.png"), taken, day)
        assertEquals("folder (8 Oct) (1).jpg", kept["folder.jpg"])
        assertEquals("FOLDER (8 Oct).png", kept["FOLDER.png"])
        assertEquals(kept.size, kept.values.map { it.lowercase() }.toSet().size)
        assertTrue(kept.values.none { it.equals(FolderPoster.NAME, ignoreCase = true) })
    }

    @Test
    fun `the day is spelled in English on every phone`() {
        val default = Locale.getDefault()
        try {
            Locale.setDefault(Locale.FRANCE)
            assertEquals("8 Oct", FolderPoster.dayOf(day))
            assertEquals("1 Jan", FolderPoster.dayOf(LocalDate.of(2027, 1, 1)))
        } finally {
            Locale.setDefault(default)
        }
    }

    @Test
    fun `what a new poster keeps leaves alone the picture it was cut from, unless it has the poster's own name`() {
        val entries = listOf(file("folder.jpg"), file("poster.jpg"), file("IMG_1.jpg"))
        assertEquals(mapOf("poster.jpg" to "poster (8 Oct).jpg"), FolderPoster.keeping(entries, "poster.jpg", day, leave = "folder.jpg"))
        assertEquals(
            mapOf("poster.jpg" to "poster (8 Oct).jpg", "folder.jpg" to "folder (8 Oct).jpg"),
            FolderPoster.keeping(entries, "poster.jpg", day, leave = "poster.jpg"),
        )
        assertEquals(emptyMap<String, String>(), FolderPoster.keeping(listOf(file("IMG_1.jpg")), "poster.jpg", day))
    }

    @Test
    fun `the poster a folder wears is the first of its pictures Regolith tries`() {
        assertEquals("poster.png", FolderPoster.worn(listOf("IMG_1.jpg", "folder.jpg", "poster.png")))
        assertEquals("folder.jpg", FolderPoster.worn(listOf("thumb.jpg", "folder.jpg")))
        assertEquals(null, FolderPoster.worn(listOf("IMG_1.jpg", "poster (8 Oct).jpg")))
    }

    @Test
    fun `a picture used whole is renamed when a player can read it as it is, and copied when not`() {
        assertEquals(WholePoster.RENAME, FolderPoster.whole("IMG_4821.JPG", 3_000_000, worn = "poster.jpg"))
        assertEquals(WholePoster.RENAME, FolderPoster.whole("swing.gif", 2_000_000, worn = null))
        assertEquals(WholePoster.COPY, FolderPoster.whole("IMG_4821.HEIC", 3_000_000, worn = null))
        assertEquals(WholePoster.COPY, FolderPoster.whole("pano.jpg", 9L * 1024 * 1024, worn = null))
        assertEquals(WholePoster.ALREADY, FolderPoster.whole("Poster.JPG", 3_000_000, worn = "poster.jpg"))
        assertEquals(WholePoster.RENAME, FolderPoster.whole("folder.jpg", 3_000_000, worn = "poster.jpg"))
    }

    @Test
    fun `a renamed picture keeps its own extension`() {
        assertEquals("poster.png", FolderPoster.renamedTo("IMG_2041.PNG"))
        assertEquals("poster.jpeg", FolderPoster.renamedTo("beach.jpeg"))
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
        val kept = FolderPoster.keptNames(listOf("poster.jpg"), taken = listOf("poster.jpg", "poster (8 Oct).jpg"), on = day, name = AnimatedPoster.NAME)
        assertEquals(mapOf("poster.jpg" to "poster (8 Oct) (1).jpg"), kept)
    }
}
