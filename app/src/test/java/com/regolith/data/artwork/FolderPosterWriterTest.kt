package com.regolith.data.artwork

import com.regolith.domain.smb.SmbCredentials
import com.regolith.domain.smb.SmbFailure
import com.regolith.domain.smb.SmbHost
import com.regolith.testing.FakeSmbGateway
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.LocalDate

/**
 * A folder poster going onto the share: poster.jpg written (or a picture
 * already there renamed into place), the pictures already acting as its
 * artwork kept under dated names, and nothing lost when the share refuses or
 * drops part of the way through.
 */
class FolderPosterWriterTest {
    private val host = SmbHost("tower")
    private val creds = SmbCredentials.Guest
    private val film = ByteArray(10) { 1 }
    private val oldFolder = ByteArray(5) { 2 }
    private val oldPoster = ByteArray(6) { 3 }
    private val picture = ByteArray(8) { 9 }
    private val gateway = FakeSmbGateway().apply { addFile("media", "Films/Heat (1995)/Heat.1995.mkv", film) }
    private val writer = FolderPosterWriter(gateway)
    private val folder = "Films/Heat (1995)"
    private val day = LocalDate.of(2026, 10, 8)

    private val files get() = gateway.files.getValue("media")
    private fun bytesAt(name: String) = files["$folder/$name"]

    private suspend fun write(name: String = "poster.jpg", leave: String? = null) =
        writer.write(host, creds, "media", folder, picture, day, name, leave)

    @Test
    fun `a folder with no picture of its own just gets the poster`() = runTest {
        val done = write()
        assertArrayEquals(picture, bytesAt("poster.jpg"))
        assertTrue(done.renamed.isEmpty())
        assertArrayEquals(film, bytesAt("Heat.1995.mkv"))
        assertFalse(files.keys.any { it.endsWith(".part") })
    }

    @Test
    fun `an old poster jpg is kept under the day's name before the new one takes its own`() = runTest {
        gateway.addFile("media", "$folder/poster.jpg", oldPoster)
        val done = write()
        assertArrayEquals(picture, bytesAt("poster.jpg"))
        assertArrayEquals(oldPoster, bytesAt("poster (8 Oct).jpg"))
        assertEquals(mapOf("poster.jpg" to "poster (8 Oct).jpg"), done.renamed)
    }

    @Test
    fun `every picture acting as artwork is kept, and the films and subtitles are left alone`() = runTest {
        gateway.addFile("media", "$folder/folder.jpg", oldFolder)
        gateway.addFile("media", "$folder/cover.PNG", oldFolder)
        gateway.addFile("media", "$folder/Heat.1995.srt", film)
        val done = write()
        assertArrayEquals(picture, bytesAt("poster.jpg"))
        assertArrayEquals(oldFolder, bytesAt("folder (8 Oct).jpg"))
        assertArrayEquals(oldFolder, bytesAt("cover (8 Oct).PNG"))
        assertNull(bytesAt("folder.jpg"))
        assertEquals(setOf("folder.jpg", "cover.PNG"), done.renamed.keys)
        assertArrayEquals(film, bytesAt("Heat.1995.mkv"))
        assertArrayEquals(film, bytesAt("Heat.1995.srt"))
    }

    @Test
    fun `a second poster the same day numbers the kept one`() = runTest {
        gateway.addFile("media", "$folder/poster.jpg", oldPoster)
        gateway.addFile("media", "$folder/poster (8 Oct).jpg", oldFolder)
        write()
        assertArrayEquals(oldPoster, bytesAt("poster (8 Oct) (1).jpg"))
        assertArrayEquals(oldFolder, bytesAt("poster (8 Oct).jpg"))
    }

    @Test
    fun `a moving poster goes up as poster gif, with an old poster jpg kept out of its way`() = runTest {
        gateway.addFile("media", "$folder/poster.jpg", oldPoster)
        write(name = "poster.gif")
        assertArrayEquals(picture, bytesAt("poster.gif"))
        assertArrayEquals(oldPoster, bytesAt("poster (8 Oct).jpg"))
        assertNull(bytesAt("poster.jpg"))
    }

    @Test
    fun `the share's root takes a poster too`() = runTest {
        writer.write(host, creds, "media", "", picture, day)
        assertArrayEquals(picture, files["poster.jpg"])
    }

    @Test
    fun `a picture the poster was cropped from stays as it is, even when it counts as artwork`() = runTest {
        gateway.addFile("media", "$folder/folder.jpg", oldFolder)
        val done = write(leave = "folder.jpg")
        assertArrayEquals(oldFolder, bytesAt("folder.jpg"))
        assertArrayEquals(picture, bytesAt("poster.jpg"))
        assertTrue(done.renamed.isEmpty())
    }

    @Test
    fun `a crop of the poster itself keeps the original under the day's name`() = runTest {
        gateway.addFile("media", "$folder/poster.jpg", oldPoster)
        write(leave = "poster.jpg")
        assertArrayEquals(oldPoster, bytesAt("poster (8 Oct).jpg"))
        assertArrayEquals(picture, bytesAt("poster.jpg"))
    }

    @Test
    fun `a picture used whole is renamed to be the poster, keeping its own extension`() = runTest {
        gateway.addFile("media", "$folder/IMG_4821.png", oldFolder)
        gateway.addFile("media", "$folder/poster.jpg", oldPoster)
        val done = writer.adopt(host, creds, "media", folder, "IMG_4821.png", day)
        assertArrayEquals(oldFolder, bytesAt("poster.png"))
        assertNull(bytesAt("IMG_4821.png"))
        assertArrayEquals(oldPoster, bytesAt("poster (8 Oct).jpg"))
        assertEquals(mapOf("poster.jpg" to "poster (8 Oct).jpg"), done.renamed)
    }

    @Test
    fun `a write that fails puts the old pictures back`() = runTest {
        gateway.addFile("media", "$folder/folder.jpg", oldFolder)
        gateway.addFile("media", "$folder/poster.jpg", oldPoster)
        gateway.writeFailure = SmbFailure.Other("disk full")
        try {
            write()
            fail("expected the write to fail")
        } catch (e: SmbFailure.Other) {
            // expected
        }
        assertArrayEquals(oldFolder, bytesAt("folder.jpg"))
        assertArrayEquals(oldPoster, bytesAt("poster.jpg"))
        assertEquals(setOf("$folder/Heat.1995.mkv", "$folder/folder.jpg", "$folder/poster.jpg"), files.keys)
    }

    @Test
    fun `a share that drops part of the way loses nothing, even if it cannot be tidied`() = runTest {
        gateway.addFile("media", "$folder/folder.jpg", oldFolder)
        // The rename of folder.jpg goes through; the share is gone for the
        // poster's own write, and for the rename back.
        gateway.unreachableAfterRenames = 1
        gateway.addFile("media", "$folder/IMG_1.jpg", picture)
        try {
            writer.adopt(host, creds, "media", folder, "IMG_1.jpg", day)
            fail("expected the share to drop")
        } catch (e: SmbFailure.Unreachable) {
            // expected
        }
        assertTrue("the old picture is still on the share", files.values.any { it.contentEquals(oldFolder) })
        assertTrue("so is the picture", files.values.any { it.contentEquals(picture) })
    }

    @Test
    fun `a read-only share refuses before anything changes`() = runTest {
        gateway.addFile("media", "$folder/folder.jpg", oldFolder)
        gateway.readOnly = true
        try {
            write()
            fail("expected Forbidden")
        } catch (e: SmbFailure.Forbidden) {
            // expected
        }
        assertArrayEquals(oldFolder, bytesAt("folder.jpg"))
        assertNull(bytesAt("poster.jpg"))
    }
}
