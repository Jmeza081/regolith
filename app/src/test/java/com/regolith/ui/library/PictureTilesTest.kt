package com.regolith.ui.library

import com.regolith.data.db.ShareFileEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A folder's other files as an album's pictures: which they are, and the marks they carry. */
class PictureTilesTest {

    private var nextId = 1L
    private fun file(name: String, folderId: Long = 7, width: Int? = null, height: Int? = null) = ShareFileEntity(
        id = nextId++, shareId = 1, folderId = folderId, relPath = "Lake/$name", name = name,
        sizeBytes = 10, modifiedAtMs = 1, width = width, height = height,
    )

    @Test
    fun `only pictures become tiles`() {
        val tiles = pictureTiles(listOf(file("IMG_1.heic"), file("notes.txt"), file("beach.en.srt"), file("RAW.dng")), emptyMap())
        assertEquals(listOf("IMG_1.heic"), tiles.map { it.name })
    }

    @Test
    fun `the poster the collection wears is marked, the sidecars it passes over are not`() {
        val tiles = pictureTiles(listOf(file("folder.jpg"), file("poster.jpg"), file("cover.png"), file("IMG_1.jpg")), emptyMap())
        assertEquals(listOf("poster.jpg"), tiles.filter { it.poster }.map { it.name })
    }

    @Test
    fun `a video's own picture names the video as its tile does`() {
        val tiles = pictureTiles(listOf(file("Rope swing.JPG"), file("IMG_1.jpg")), mapOf("rope swing.mp4" to "Rope swing", "Heat.1995.mkv" to "Heat (1995)"))
        assertEquals("Rope swing", tiles.first { it.name == "Rope swing.JPG" }.videoName)
        assertNull(tiles.first { it.name == "IMG_1.jpg" }.videoName)
    }

    @Test
    fun `a picture's shape is its own once measured, square until then`() {
        val tiles = pictureTiles(listOf(file("tall.jpg", width = 3024, height = 4032), file("new.jpg")), emptyMap())
        assertEquals(0.75f, tiles[0].aspect, 0.001f)
        assertEquals(1f, tiles[1].aspect, 0.001f)
    }

    @Test
    fun `a GIF says so, and its title drops the extension`() {
        val tiles = pictureTiles(listOf(file("Sparkler.gif"), file("IMG_4821.HEIC")), emptyMap())
        assertTrue(tiles[0].gif)
        assertFalse(tiles[1].gif)
        assertEquals("IMG_4821", tiles[1].title)
    }
}
