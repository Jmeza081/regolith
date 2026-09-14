package com.regolith.desktop.ui

import com.regolith.desktop.AppGraph
import com.regolith.desktop.data.Connection
import com.regolith.desktop.navigation.Route
import com.regolith.desktop.ui.browse.BrowseViewModel
import com.regolith.domain.smb.SmbCredentials
import com.regolith.domain.smb.SmbEntry
import com.regolith.domain.smb.SmbHost
import com.regolith.testing.FakeSmbGateway
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class BrowseViewModelTest {
    private fun file(name: String) = SmbEntry(name, isDirectory = false, sizeBytes = 1, modifiedAtMs = 0)
    private fun dir(name: String) = SmbEntry(name, isDirectory = true, sizeBytes = 0, modifiedAtMs = 0)

    @Test
    fun `folders come first, only films are listed, and a sidecar marks its film`() {
        val rows = BrowseViewModel.rowsFor(
            listOf(
                file("notes.txt"), file("Zodiac.2007.mkv"), dir("series"), file("poster.jpg"),
                file("Heat.1995.mkv"), file("Heat.1995.chapters.txt"), dir("Archive"),
            ),
        )
        assertEquals(listOf("Archive", "series", "Heat.1995.mkv", "Zodiac.2007.mkv"), rows.map { it.entry.name })
        assertEquals(listOf(false, false, true, false), rows.map { it.hasChapters })
    }

    @Test
    fun `a film carries the image the phone would pick from the same listing`() {
        // Two films share this folder, so poster.jpg is the folder's, not Zodiac's; Heat has its own.
        val rows = BrowseViewModel.rowsFor(listOf(file("Heat.1995.mkv"), file("Heat.1995.jpg"), file("Zodiac.2007.mkv"), file("poster.jpg")))
        assertEquals(mapOf("Heat.1995.mkv" to "Heat.1995.jpg", "Zodiac.2007.mkv" to null), rows.associate { it.entry.name to it.image?.name })

        // A film alone in its folder takes the folder's poster.
        val alone = BrowseViewModel.rowsFor(listOf(file("Arrival.2016.mp4"), file("poster.jpg")))
        assertEquals("poster.jpg", alone.single().image?.name)
    }

    @Test
    fun `a subfolder lists its own entries and is titled by its name`() = runTest {
        val fake = FakeSmbGateway().apply {
            addFile("media", "Films/Heat.1995.mkv", ByteArray(3))
            addFile("media", "Films/Noir/Laura.1944.mp4", ByteArray(3))
        }
        val route = Route.Browse(Connection(SmbHost("tower"), SmbCredentials.Guest, "media"), folder = "Films")
        val vm = BrowseViewModel(AppGraph(fake), route, this)
        advanceUntilIdle()
        val s = vm.state.value
        assertEquals("Films", s.title)
        assertEquals("tower/media/Films", s.location)
        assertEquals(listOf("Noir", "Heat.1995.mkv"), s.rows.map { it.entry.name })
    }

    @Test
    fun `an unreachable share shows the problem instead of an empty folder`() = runTest {
        val fake = FakeSmbGateway().apply { reachable = false }
        val route = Route.Browse(Connection(SmbHost("tower"), SmbCredentials.Guest, "media"))
        val vm = BrowseViewModel(AppGraph(fake), route, this)
        advanceUntilIdle()
        assertEquals("tower is out of reach", vm.state.value.problem?.message)
        assertEquals(false, vm.state.value.loading)
    }
}
