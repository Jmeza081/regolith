package com.regolith.desktop.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Finding the bundle is file-listing only; nothing native is loaded here. */
class NativeVlcTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun `the bundled strategy finds a lib folder that has both libraries, and nothing else`() {
        val lib = tmp.newFolder("vlc", "lib")
        assertNull(BundledVlcStrategy(lib).discover())
        File(lib, "libvlc.dylib").writeText("")
        assertNull("libvlccore is needed too", BundledVlcStrategy(lib).discover())
        File(lib, "libvlccore.dylib").writeText("")
        assertEquals(lib.absolutePath, BundledVlcStrategy(lib).discover())
    }

    @Test
    fun `the packaged app's copy is tried before the checkout's`() {
        val before = System.getProperty("compose.application.resources.dir")
        try {
            System.setProperty("compose.application.resources.dir", "/Applications/Regolith Chapters.app/Contents/app/resources")
            val dirs = NativeVlc.candidateLibDirs()
            assertEquals(File("/Applications/Regolith Chapters.app/Contents/app/resources/vlc/lib"), dirs[0])
            assertEquals(File("vlc-bundle/macos-arm64/vlc/lib").absoluteFile, dirs[1])
        } finally {
            if (before == null) System.clearProperty("compose.application.resources.dir") else System.setProperty("compose.application.resources.dir", before)
        }
    }
}
