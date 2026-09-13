package com.regolith.data.media

import com.regolith.domain.smb.SmbCredentials
import com.regolith.domain.smb.SmbFailure
import com.regolith.domain.smb.SmbHost
import com.regolith.testing.FakeSmbGateway
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SidecarWriterTest {
    private val host = SmbHost("tower")
    private val creds = SmbCredentials.Guest
    private val gateway = FakeSmbGateway().apply { addFile("media", "Films/Heat.1995.mkv", ByteArray(10)) }
    private val writer = SidecarWriter(gateway)

    @Test
    fun `writes through a part file and leaves only the real name`() = runTest {
        val mtime = writer.write(host, creds, "media", "Films", "Heat.1995.mkv", "CHAPTER01=00:00:00.000\nCHAPTER01NAME=Intro\n")
        val all = gateway.files.getValue("media")
        assertTrue(all.containsKey("Films/Heat.1995.chapters.txt"))
        assertFalse(all.keys.any { it.endsWith(".part") })
        assertEquals(mtime, gateway.mtime("media", "Films/Heat.1995.chapters.txt"))
        assertEquals("Intro", writer.read(host, creds, "media", "Films/Heat.1995.chapters.txt")!!.lines()[1].substringAfter('='))
    }

    @Test
    fun `a server that will not rename over an existing file gets a delete first`() = runTest {
        gateway.renameOverExistingFails = true
        writer.write(host, creds, "media", "Films", "Heat.1995.mkv", "one\n")
        writer.write(host, creds, "media", "Films", "Heat.1995.mkv", "two\n")
        assertEquals("two\n", String(gateway.files.getValue("media").getValue("Films/Heat.1995.chapters.txt")))
        assertFalse(gateway.files.getValue("media").keys.any { it.endsWith(".part") })
    }

    @Test
    fun `a read-only share refuses, and nothing is left behind`() = runTest {
        gateway.readOnly = true
        try {
            writer.write(host, creds, "media", "Films", "Heat.1995.mkv", "x\n")
            fail("expected Forbidden")
        } catch (e: SmbFailure.Forbidden) {
            // expected
        }
        assertEquals(setOf("Films/Heat.1995.mkv"), gateway.files.getValue("media").keys)
    }

    @Test
    fun `the share root has no folder prefix, and delete tolerates a missing file`() = runTest {
        assertEquals("Heat.1995.chapters.txt", SidecarWriter.pathFor("", "Heat.1995.mkv", ".chapters.txt"))
        writer.delete(host, creds, "media", "Films", "Heat.1995.mkv")
        assertNull(gateway.files.getValue("media")["Films/Heat.1995.chapters.txt"])
    }

    @Test
    fun `a file too big to be a chapter file reads as nothing`() = runTest {
        gateway.addFile("media", "Films/Heat.1995.chapters.txt", ByteArray(70 * 1024))
        assertNull(writer.read(host, creds, "media", "Films/Heat.1995.chapters.txt"))
    }
}
