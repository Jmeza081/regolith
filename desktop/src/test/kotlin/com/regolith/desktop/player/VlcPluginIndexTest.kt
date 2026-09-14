package com.regolith.desktop.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

/** File work only; libvlc is not loaded. */
class VlcPluginIndexTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun plugins(): File = tmp.newFolder("app", "vlc", "plugins").also { p ->
        File(p, "libavcodec_plugin.dylib").writeText("codec")
        File(p, "libmp4_plugin.dylib").writeText("mp4")
        File(p, "plugins.dat").writeText("videolan's stale index")
        File(p, "misc").mkdirs()
        File(p, "misc/libdummy_plugin.dylib").writeText("dummy")
    }

    @Test
    fun `the linked folder has a link per plugin, not VideoLAN's index, and is not ready until indexed`() {
        val src = plugins()
        val root = tmp.newFolder("support")
        val p = VlcPluginIndex.prepare(src, root)
        assertFalse(p.ready)
        val links = p.dir.walkTopDown().filter { Files.isSymbolicLink(it.toPath()) }.map { it.relativeTo(p.dir).path }.sorted().toList()
        assertEquals(listOf("libavcodec_plugin.dylib", "libmp4_plugin.dylib", "misc/libdummy_plugin.dylib"), links)
        assertEquals("codec", File(p.dir, "libavcodec_plugin.dylib").readText())
        assertFalse(File(p.dir, "plugins.dat").exists())
    }

    @Test
    fun `an index written there makes the folder ready and it is reused`() {
        val src = plugins()
        val root = tmp.newFolder("support")
        val first = VlcPluginIndex.prepare(src, root)
        assertFalse("no index yet", VlcPluginIndex.markIndexed(first))
        File(first.dir, "plugins.dat").writeText("libvlc's fresh index")
        assertTrue(VlcPluginIndex.markIndexed(first))
        val again = VlcPluginIndex.prepare(src, root)
        assertEquals(first.dir, again.dir)
        assertTrue(again.ready)
    }

    @Test
    fun `a changed plugin gets a new folder, the old one goes, and the plugins themselves are untouched`() {
        val src = plugins()
        val root = tmp.newFolder("support")
        val first = VlcPluginIndex.prepare(src, root)
        File(src, "libmp4_plugin.dylib").writeText("mp4, updated")
        val second = VlcPluginIndex.prepare(src, root)
        assertNotEquals(first.dir, second.dir)
        assertFalse(first.dir.exists())
        assertFalse(second.ready)
        assertEquals(listOf(second.dir.name), root.list()!!.toList())
        assertEquals("codec", File(src, "libavcodec_plugin.dylib").readText())
        assertEquals("mp4, updated", File(src, "libmp4_plugin.dylib").readText())
        assertTrue(File(src, "plugins.dat").exists())
    }

    @Test
    fun `the same plugins in another place are a different folder`() {
        val a = plugins()
        val b = tmp.newFolder("elsewhere", "plugins").also { a.copyRecursively(it, overwrite = true) }
        assertNotEquals(VlcPluginIndex.stamp(a.canonicalFile), VlcPluginIndex.stamp(b.canonicalFile))
    }
}
