package com.regolith.data.smb

import com.regolith.domain.smb.SmbCredentials
import com.regolith.domain.smb.SmbFailure
import com.regolith.domain.smb.SmbHost
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.net.Socket

/**
 * The real jcifs client against the local Samba fixture (localhost:1445,
 * share `media` = ~/RegolithShare). Skipped when the fixture is not running.
 *
 * Guards the create-on-open bug: jcifs-ng opens files for reading with
 * create-if-missing, which a fake gateway cannot imitate.
 */
class JcifsGatewaySambaTest {
    private val root = File(System.getProperty("user.home"), "RegolithShare")
    private val host = SmbHost("localhost", 1445)
    private val guest = SmbCredentials.Guest

    private fun assumeFixture() {
        assumeTrue("Samba fixture on localhost:1445 is not running", runCatching { Socket("localhost", 1445).close() }.isSuccess)
        assumeTrue("fixture folder missing", File(root, "Films").isDirectory)
    }

    @Test
    fun `opening a path that is not there fails as not found and creates nothing`() = runBlocking {
        assumeFixture()
        val gateway = JcifsGateway()
        gateway.list(host, guest, "media", "Films") // a real connect lists first, which settles the dialect
        val relPath = "Films/regolith-open-probe-${System.nanoTime()}.chapters.txt"
        val onDisk = File(root, relPath)
        try {
            gateway.open(host, guest, "media", relPath).close()
            fail("opening a missing file should fail")
        } catch (e: SmbFailure.NotFound) {
            // expected
        } finally {
            val created = onDisk.exists()
            if (created && onDisk.length() == 0L) onDisk.delete()
            assertFalse("opening a missing file created it on the share", created)
        }
    }

    @Test
    fun `an existing file still opens and reads`() = runBlocking {
        assumeFixture()
        val gateway = JcifsGateway()
        gateway.list(host, guest, "media", "Films")
        gateway.open(host, guest, "media", "Films/Long.Test.2026.mp4").use { src ->
            val buf = ByteArray(16)
            assertTrue(src.size > 1_000_000)
            assertTrue(src.readAt(4, buf, 0, 4) > 0)
            assertTrue("an MP4 has 'ftyp' at byte 4", String(buf, 0, 4) == "ftyp")
        }
    }
}
