package com.regolith.desktop.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files

class ServerStoreTest {
    @get:Rule val tmp = TemporaryFolder()
    private val file by lazy { tmp.root.toPath().resolve("Regolith Chapters/servers.json") }
    private fun store() = ServerStore(file, io = Dispatchers.Unconfined)

    @Test
    fun `nothing saved reads as empty and creates no file`() = runTest {
        assertTrue(store().all().isEmpty())
        assertFalse(Files.exists(file))
    }

    @Test
    fun `saves get ids, survive a restart, and never hold a password`() = runTest {
        val a = store().save(SavedServer(host = "tower", share = "media", username = "sam"))
        val b = store().save(SavedServer(host = "localhost", port = 1445, share = "media"))
        assertEquals(listOf(1L, 2L), listOf(a.id, b.id))
        assertEquals(listOf(a, b), store().all())
        assertFalse(Files.readString(file).contains("password", ignoreCase = true))
    }

    @Test
    fun `saving the same share and sign-in again updates the row instead of adding one`() = runTest {
        val s = store()
        val first = s.save(SavedServer(host = "tower", share = "media", username = "sam"))
        val again = s.save(SavedServer(host = "TOWER", share = "Media", username = "sam"))
        assertEquals(first.id, again.id)
        assertEquals(1, s.all().size)
        // A different user on the same share is a different row.
        s.save(SavedServer(host = "tower", share = "media", username = "alex"))
        assertEquals(2, s.all().size)
    }

    @Test
    fun `saving with an id replaces that row, even when the address changed`() = runTest {
        val s = store()
        val row = s.save(SavedServer(host = "tower", share = "media"))
        s.save(row.copy(host = "192.168.4.73"))
        assertEquals(listOf(row.copy(host = "192.168.4.73")), s.all())
    }

    @Test
    fun `remove deletes one row`() = runTest {
        val s = store()
        val a = s.save(SavedServer(host = "a", share = "m"))
        val b = s.save(SavedServer(host = "b", share = "m"))
        s.remove(a.id)
        assertEquals(listOf(b), s.all())
    }

    @Test
    fun `an unreadable file is moved aside, not overwritten, and saving starts fresh`() = runTest {
        Files.createDirectories(file.parent)
        Files.writeString(file, "{ this is not json")
        val s = store()
        assertTrue(s.all().isEmpty())
        assertTrue(Files.exists(file.resolveSibling("servers.json.bad")))
        assertEquals(1L, s.save(SavedServer(host = "tower", share = "media")).id)
    }
}
