package com.regolith.data.artwork

import com.regolith.domain.artwork.ExistingArtwork
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

/**
 * A folder poster going onto the share (P19): poster.jpg written, the
 * pictures already there deleted or renamed, and nothing lost when the share
 * refuses or drops part of the way through.
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

    private val files get() = gateway.files.getValue("media")
    private fun bytesAt(name: String) = files["$folder/$name"]

    private suspend fun write(existing: ExistingArtwork) = writer.write(host, creds, "media", folder, picture, existing)

    @Test
    fun `a folder with no picture of its own just gets the poster`() = runTest {
        val done = write(ExistingArtwork.KEEP)
        assertArrayEquals(picture, bytesAt("poster.jpg"))
        assertTrue(done.renamed.isEmpty() && done.deleted.isEmpty())
        assertArrayEquals(film, bytesAt("Heat.1995.mkv"))
        assertFalse(files.keys.any { it.endsWith(".part") })
    }

    @Test
    fun `a moving poster goes up as poster gif, and an old poster jpg that would outrank it goes`() = runTest {
        gateway.addFile("media", "$folder/poster.jpg", oldPoster)
        val done = writer.write(host, creds, "media", folder, picture, ExistingArtwork.REPLACE, name = "poster.gif")
        assertArrayEquals(picture, bytesAt("poster.gif"))
        assertEquals(listOf("poster.jpg"), done.deleted)
        assertNull(bytesAt("poster.jpg"))
    }

    @Test
    fun `kept beside a moving poster, an old poster jpg is renamed out of the way`() = runTest {
        gateway.addFile("media", "$folder/poster.jpg", oldPoster)
        writer.write(host, creds, "media", folder, picture, ExistingArtwork.KEEP, name = "poster.gif")
        assertArrayEquals(picture, bytesAt("poster.gif"))
        assertArrayEquals(oldPoster, bytesAt("poster (1).jpg"))
        assertNull(bytesAt("poster.jpg"))
    }

    @Test
    fun `the share's root takes a poster too`() = runTest {
        writer.write(host, creds, "media", "", picture, ExistingArtwork.KEEP)
        assertArrayEquals(picture, files["poster.jpg"])
    }

    @Test
    fun `replace deletes the folder's other pictures and leaves its films alone`() = runTest {
        gateway.addFile("media", "$folder/folder.jpg", oldFolder)
        gateway.addFile("media", "$folder/cover.PNG", oldFolder)
        gateway.addFile("media", "$folder/Heat.1995.srt", film)
        val done = write(ExistingArtwork.REPLACE)
        assertArrayEquals(picture, bytesAt("poster.jpg"))
        assertEquals(null, bytesAt("folder.jpg"))
        assertEquals(null, bytesAt("cover.PNG"))
        assertEquals(setOf("folder.jpg", "cover.PNG"), done.deleted.toSet())
        assertArrayEquals(film, bytesAt("Heat.1995.mkv"))
        assertArrayEquals(film, bytesAt("Heat.1995.srt"))
    }

    @Test
    fun `replace writes over an old poster jpg rather than deleting it first`() = runTest {
        gateway.addFile("media", "$folder/poster.jpg", oldPoster)
        val done = write(ExistingArtwork.REPLACE)
        assertArrayEquals(picture, bytesAt("poster.jpg"))
        assertTrue(done.deleted.isEmpty())
    }

    @Test
    fun `replace that cannot write deletes nothing`() = runTest {
        gateway.addFile("media", "$folder/folder.jpg", oldFolder)
        gateway.writeFailure = SmbFailure.Other("disk full")
        try {
            write(ExistingArtwork.REPLACE)
            fail("expected the write to fail")
        } catch (e: SmbFailure.Other) {
            // expected
        }
        assertArrayEquals(oldFolder, bytesAt("folder.jpg"))
        assertEquals(null, bytesAt("poster.jpg"))
    }

    @Test
    fun `keep renames the old picture out of the way, bytes and all`() = runTest {
        gateway.addFile("media", "$folder/folder.jpg", oldFolder)
        val done = write(ExistingArtwork.KEEP)
        assertArrayEquals(picture, bytesAt("poster.jpg"))
        assertArrayEquals(oldFolder, bytesAt("folder (1).jpg"))
        assertEquals(null, bytesAt("folder.jpg"))
        assertEquals(mapOf("folder.jpg" to "folder (1).jpg"), done.renamed)
    }

    @Test
    fun `keep moves an old poster jpg aside before the new one takes its name`() = runTest {
        gateway.addFile("media", "$folder/poster.jpg", oldPoster)
        write(ExistingArtwork.KEEP)
        assertArrayEquals(picture, bytesAt("poster.jpg"))
        assertArrayEquals(oldPoster, bytesAt("poster (1).jpg"))
    }

    @Test
    fun `keep that cannot write puts the old pictures back`() = runTest {
        gateway.addFile("media", "$folder/folder.jpg", oldFolder)
        gateway.addFile("media", "$folder/poster.jpg", oldPoster)
        gateway.writeFailure = SmbFailure.Other("disk full")
        try {
            write(ExistingArtwork.KEEP)
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
        // poster's own rename, and for the rename back.
        gateway.unreachableAfterRenames = 1
        try {
            write(ExistingArtwork.KEEP)
            fail("expected the share to drop")
        } catch (e: SmbFailure.Unreachable) {
            // expected
        }
        assertTrue("the old picture is still on the share", files.values.any { it.contentEquals(oldFolder) })
    }

    @Test
    fun `a read-only share refuses before anything changes`() = runTest {
        gateway.addFile("media", "$folder/folder.jpg", oldFolder)
        gateway.readOnly = true
        for (existing in ExistingArtwork.entries) {
            try {
                write(existing)
                fail("expected Forbidden")
            } catch (e: SmbFailure.Forbidden) {
                // expected
            }
            assertArrayEquals(oldFolder, bytesAt("folder.jpg"))
            assertEquals(null, bytesAt("poster.jpg"))
        }
    }
}
