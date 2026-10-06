package com.regolith.domain.spoof

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Made-up names: the same every time, never the real one, and the library keeps its shape. */
class SpoofNamesTest {

    private val salt = 0x5eed_1234_abcdL

    @Test
    fun `a name is made up the same way every time, and differently on another install`() {
        val once = SpoofNames.title("Hawaii 2019 - day 1", salt)
        assertEquals(once, SpoofNames.title("Hawaii 2019 - day 1", salt))
        assertNotEquals("Hawaii 2019 - day 1", once)
        assertNotEquals(once, SpoofNames.title("Hawaii 2019 - day 1", salt + 1))
    }

    @Test
    fun `a made-up title reads as one`() {
        val words = SpoofNames.title("GH010423", salt).removePrefix("The ").split(' ')
        assertEquals(2, words.size)
        assertTrue(words.all { it.first().isUpperCase() })
    }

    @Test
    fun `different names mostly get different titles`() {
        val titles = (1..200).map { SpoofNames.title("clip $it", salt) }.toSet()
        assertTrue("only ${titles.size} distinct titles for 200 names", titles.size > 180)
    }

    @Test
    fun `a file keeps its extension, and its title is the same words`() {
        val name = SpoofNames.fileName("Arrival.2016.2160p.mkv", salt)
        assertTrue(name.endsWith(".mkv"))
        assertEquals(SpoofNames.titleOfFile("Arrival.2016.2160p.mkv", salt) + ".mkv", name)
        assertFalse(name.contains("Arrival"))
    }

    @Test
    fun `a folder's own picture keeps its name`() {
        assertEquals("poster.jpg", SpoofNames.fileName("poster.jpg", salt))
        assertEquals("folder.png", SpoofNames.fileName("folder.png", salt))
    }

    @Test
    fun `folder names everyone has are kept, and a film folder keeps its year`() {
        assertEquals("Season 01", SpoofNames.folderName("Season 01", salt))
        assertEquals("Extras", SpoofNames.folderName("Extras", salt))
        assertEquals("DCIM", SpoofNames.folderName("DCIM", salt))
        val film = SpoofNames.folderName("Arrival (2016)", salt)
        assertTrue(film.endsWith(" (2016)"))
        assertFalse(film.contains("Arrival"))
    }

    @Test
    fun `a path is made up part by part, as each part's own tile is`() {
        val path = SpoofNames.path("Home videos/Lake house 2024/Rope swing.mp4", salt)
        val parts = path.split('/')
        assertEquals(SpoofNames.folderName("Home videos", salt), parts[0])
        assertEquals(SpoofNames.folderName("Lake house 2024", salt), parts[1])
        assertEquals(SpoofNames.fileName("Rope swing.mp4", salt), parts[2])
        assertEquals("", SpoofNames.path("", salt))
        assertEquals(SpoofNames.folderName("Home videos", salt) + "/Season 02", SpoofNames.path("Home videos/Season 02", salt))
    }

    @Test
    fun `a photo is picked by the owner's id and gives nothing of it away`() {
        val seed = SpoofNames.imageSeed("file:42:", salt)
        assertEquals(seed, SpoofNames.imageSeed("file:42:", salt))
        assertNotEquals(seed, SpoofNames.imageSeed("file:43:", salt))
        assertFalse(seed.contains("file"))
        assertTrue(seed.matches(Regex("rg[0-9a-f]+")))
    }
}
