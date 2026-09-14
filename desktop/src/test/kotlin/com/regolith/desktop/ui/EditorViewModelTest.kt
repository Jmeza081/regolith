package com.regolith.desktop.ui

import com.regolith.desktop.AppGraph
import com.regolith.desktop.FakeFilmPlayer
import com.regolith.desktop.data.Connection
import com.regolith.desktop.navigation.Route
import com.regolith.desktop.ui.editor.EditorViewModel
import com.regolith.domain.smb.SmbCredentials
import com.regolith.domain.smb.SmbEntry
import com.regolith.domain.smb.SmbHost
import com.regolith.testing.FakeSmbGateway
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorViewModelTest {
    private val sidecar = "Films/Heat.1995.chapters.txt"
    private val fake = FakeSmbGateway().apply { addFile("media", "Films/Heat.1995.mkv", ByteArray(64)) }
    private val player = FakeFilmPlayer()
    private val route = Route.Editor(
        Connection(SmbHost("tower"), SmbCredentials.Guest, "media"),
        folder = "Films",
        video = SmbEntry("Heat.1995.mkv", isDirectory = false, sizeBytes = 64, modifiedAtMs = 0),
    )

    private fun TestScope.vm() = EditorViewModel(AppGraph(fake), route, this, player, io = StandardTestDispatcher(testScheduler))

    private fun text(path: String) = fake.files["media"]?.get(path)?.toString(Charsets.UTF_8)

    @Test
    fun `opening a film plays it and seeds the draft from its chapter file`() = runTest {
        fake.addFile("media", sidecar, "CHAPTER01=00:00:00.000\nCHAPTER01NAME=Intro\nCHAPTER02=00:12:30.000\nCHAPTER02NAME=The heist\n".toByteArray())
        val vm = vm()
        advanceUntilIdle()
        val s = vm.state.value
        assertNotNull(player.opened)
        assertTrue(s.hasSidecar)
        assertEquals(listOf("Intro", "The heist"), s.draft!!.marks.map { it.title })
        assertFalse(s.draft!!.dirty)
    }

    @Test
    fun `a film with no chapter file starts from one unnamed start mark`() = runTest {
        val vm = vm()
        advanceUntilIdle()
        assertFalse(vm.state.value.hasSidecar)
        assertEquals(1, vm.state.value.draft!!.marks.size)
        assertNull(vm.state.value.problem)
    }

    @Test
    fun `learning the runtime drops chapters past the end and keeps the rest`() = runTest {
        fake.addFile("media", sidecar, "CHAPTER01=00:00:00.000\nCHAPTER01NAME=\nCHAPTER02=00:01:00.000\nCHAPTER02NAME=In\nCHAPTER03=00:09:00.000\nCHAPTER03NAME=Out\n".toByteArray())
        val vm = vm()
        advanceUntilIdle()
        vm.onDurationKnown(5 * 60_000L)
        assertEquals(listOf(0L, 60_000L), vm.state.value.draft!!.marks.map { it.startMs })
    }

    @Test
    fun `mark, rename and save write the phone's format next to the film`() = runTest {
        val vm = vm()
        advanceUntilIdle()
        vm.onDurationKnown(40 * 60_000L)
        player.positionMs = 750_000
        vm.mark()
        vm.rename(1, "The heist")
        assertTrue(vm.state.value.canSave)
        vm.save()
        advanceUntilIdle()
        assertEquals("CHAPTER01=00:00:00.000\nCHAPTER01NAME=\nCHAPTER02=00:12:30.000\nCHAPTER02NAME=The heist\n", text(sidecar))
        assertEquals("Saved to the share", vm.state.value.message)
        assertFalse(vm.state.value.draft!!.dirty)
        assertTrue(vm.state.value.hasSidecar)
        assertNull("the temporary .part file is gone", text("$sidecar.part"))
    }

    @Test
    fun `a read-only share leaves the edits unsaved and says why`() = runTest {
        val vm = vm()
        advanceUntilIdle()
        vm.onDurationKnown(40 * 60_000L)
        player.positionMs = 60_000
        vm.mark()
        fake.readOnly = true
        vm.save()
        advanceUntilIdle()
        assertEquals("Not saved: the share is read-only", vm.state.value.message)
        assertTrue(vm.state.value.draft!!.dirty)
        assertNull(text(sidecar))
    }

    @Test
    fun `opening a chapter and nudging it take the film there`() = runTest {
        fake.addFile("media", sidecar, "CHAPTER01=00:00:00.000\nCHAPTER01NAME=\nCHAPTER02=00:02:00.000\nCHAPTER02NAME=\n".toByteArray())
        val vm = vm()
        advanceUntilIdle()
        vm.onDurationKnown(10 * 60_000L)
        vm.select(1)
        vm.nudge(1, 5_000)
        assertEquals(listOf(120_000L, 125_000L), player.seeks)
        assertEquals(1, vm.state.value.draft!!.selected)
    }

    @Test
    fun `a film that is gone shows the problem instead of an editor`() = runTest {
        fake.files["media"]!!.remove("Films/Heat.1995.mkv")
        val vm = vm()
        advanceUntilIdle()
        assertEquals("media/Films/Heat.1995.mkv was not found", vm.state.value.problem?.message)
        assertNull(vm.state.value.draft)
    }
}
