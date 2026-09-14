package com.regolith.desktop.ui

import com.regolith.desktop.AppGraph
import com.regolith.desktop.FakeFilmPlayer
import com.regolith.desktop.RecordingGateway
import com.regolith.desktop.data.Connection
import com.regolith.desktop.navigation.Route
import com.regolith.desktop.ui.editor.EditorViewModel
import com.regolith.domain.playback.Chapter
import com.regolith.desktop.ui.editor.ChapterOrigin
import com.regolith.domain.smb.SeekableByteSource
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

    private fun TestScope.vm(readEmbedded: (SeekableByteSource) -> List<Chapter> = { emptyList() }) =
        EditorViewModel(AppGraph(fake), route, this, player, io = StandardTestDispatcher(testScheduler), readEmbedded = readEmbedded)

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

    @Test
    fun `a typed start moves the mark and the film, and bad times say why in the phone's words`() = runTest {
        fake.addFile("media", sidecar, "CHAPTER01=00:00:00.000\nCHAPTER01NAME=\nCHAPTER02=00:02:00.000\nCHAPTER02NAME=\nCHAPTER03=00:05:00.000\nCHAPTER03NAME=\n".toByteArray())
        val vm = vm()
        advanceUntilIdle()
        vm.onDurationKnown(10 * 60_000L)

        assertNull(vm.typeStart(1, "1:30.5"))
        assertEquals(90_500L, vm.state.value.draft!!.marks[1].startMs)
        assertEquals(90_500L, player.seeks.last())

        assertEquals("Use 12:30, 0:12:30 or 1:02:15.5", vm.typeStart(1, "soon"))
        assertEquals("Between 0:01 and 4:59 here", vm.typeStart(1, "6:00"))
        assertEquals("This one cannot move", vm.typeStart(0, "0:10"))
        assertEquals("the failed tries moved nothing", 90_500L, vm.state.value.draft!!.marks[1].startMs)
    }

    @Test
    fun `remove all leaves one unnamed start mark and is saved only on Save`() = runTest {
        fake.addFile("media", sidecar, "CHAPTER01=00:00:00.000\nCHAPTER01NAME=Intro\nCHAPTER02=00:02:00.000\nCHAPTER02NAME=\n".toByteArray())
        val vm = vm()
        advanceUntilIdle()
        assertTrue(vm.state.value.canClearAll)
        vm.clearAll()
        assertEquals(listOf(Chapter(0, null)), vm.state.value.draft!!.marks)
        assertFalse(vm.state.value.canClearAll)
        assertTrue(text(sidecar)!!.contains("Intro"))
    }

    @Test
    fun `revert deletes the chapter file and starts again from the start mark`() = runTest {
        fake.addFile("media", sidecar, "CHAPTER01=00:00:00.000\nCHAPTER01NAME=Intro\n".toByteArray())
        val vm = vm()
        advanceUntilIdle()
        vm.askRevert()
        assertTrue(vm.state.value.revertAsked)
        vm.confirmRevert()
        advanceUntilIdle()
        assertNull(text(sidecar))
        assertFalse(vm.state.value.hasSidecar)
        assertEquals(listOf(Chapter(0, null)), vm.state.value.draft!!.marks)
        assertEquals("Reverted: the chapter file is gone", vm.state.value.message)
    }

    @Test
    fun `revert on a read-only share keeps the file and says so`() = runTest {
        fake.addFile("media", sidecar, "CHAPTER01=00:00:00.000\nCHAPTER01NAME=Intro\n".toByteArray())
        val vm = vm()
        advanceUntilIdle()
        fake.readOnly = true
        vm.askRevert()
        vm.confirmRevert()
        advanceUntilIdle()
        assertEquals("Not reverted: the share is read-only", vm.state.value.message)
        assertTrue(vm.state.value.hasSidecar)
        assertEquals("Intro", vm.state.value.draft!!.marks[0].title)
    }

    @Test
    fun `revert is not offered for a film with no chapter file`() = runTest {
        val vm = vm()
        advanceUntilIdle()
        vm.askRevert()
        assertFalse(vm.state.value.revertAsked)
    }

    @Test
    fun `the save callback runs only when the file was written`() = runTest {
        val vm = vm()
        advanceUntilIdle()
        vm.onDurationKnown(60_000L)
        player.positionMs = 30_000
        vm.mark()
        var saved = 0
        fake.readOnly = true
        vm.save { saved++ }
        advanceUntilIdle()
        assertEquals(0, saved)
        fake.readOnly = false
        vm.save { saved++ }
        advanceUntilIdle()
        assertEquals(1, saved)
    }

    @Test
    fun `with no video, Add chapter puts one a minute after the last and opens it for typing`() = runTest {
        val vm = vm()
        advanceUntilIdle()
        vm.addChapter()
        vm.addChapter()
        val d = vm.state.value.draft!!
        assertEquals(listOf(0L, 60_000L, 120_000L), d.marks.map { it.startMs })
        assertEquals(2, d.selected)
        assertNull(vm.typeStart(2, "12:30"))
        assertEquals(750_000L, vm.state.value.draft!!.marks[2].startMs)
    }

    @Test
    fun `a film with no chapter file starts from the chapters inside it, and Save writes them out unchanged`() = runTest {
        val vm = vm(readEmbedded = { listOf(Chapter(0, "Opening"), Chapter(20_000, "The middle")) })
        advanceUntilIdle()
        val s = vm.state.value
        assertEquals(ChapterOrigin.EMBEDDED, s.origin)
        assertEquals(listOf("Opening", "The middle"), s.draft!!.marks.map { it.title })
        assertFalse("nothing edited, so leaving does not ask", s.draft!!.dirty)
        assertTrue("but they can be saved as a chapter file", s.canSave)
        vm.save()
        advanceUntilIdle()
        assertEquals("CHAPTER01=00:00:00.000\nCHAPTER01NAME=Opening\nCHAPTER02=00:00:20.000\nCHAPTER02NAME=The middle\n", text(sidecar))
        assertEquals(ChapterOrigin.FILE, vm.state.value.origin)
        assertFalse(vm.state.value.canSave)
    }

    @Test
    fun `a chapter file wins, and the chapters inside the film are not even read`() = runTest {
        fake.addFile("media", sidecar, "CHAPTER01=00:00:00.000\nCHAPTER01NAME=Mine\n".toByteArray())
        var reads = 0
        val vm = vm(readEmbedded = { reads++; listOf(Chapter(0, "Theirs")) })
        advanceUntilIdle()
        assertEquals(0, reads)
        assertEquals(ChapterOrigin.FILE, vm.state.value.origin)
        assertEquals("Mine", vm.state.value.draft!!.marks[0].title)
    }

    @Test
    fun `revert falls back to the chapters inside the film`() = runTest {
        fake.addFile("media", sidecar, "CHAPTER01=00:00:00.000\nCHAPTER01NAME=Mine\n".toByteArray())
        val vm = vm(readEmbedded = { listOf(Chapter(0, "Opening"), Chapter(20_000, "The middle")) })
        advanceUntilIdle()
        vm.askRevert()
        vm.confirmRevert()
        advanceUntilIdle()
        assertNull(text(sidecar))
        assertEquals(ChapterOrigin.EMBEDDED, vm.state.value.origin)
        assertEquals(listOf("Opening", "The middle"), vm.state.value.draft!!.marks.map { it.title })
    }

    @Test
    fun `names from the folder's chapter files are gathered for suggestions, and other files are ignored`() = runTest {
        fake.addFile("media", "Films/Ronin.1998.chapters.txt", "CHAPTER01=00:00:00.000\nCHAPTER01NAME=Intro\nCHAPTER02=00:10:00.000\nCHAPTER02NAME=The heist\n".toByteArray())
        fake.addFile("media", "Films/Thief.1981.chapters.txt", "CHAPTER01=00:00:00.000\nCHAPTER01NAME=intro\nCHAPTER02=00:30:00.000\nCHAPTER02NAME=\n".toByteArray())
        fake.addFile("media", "Films/notes.txt", "CHAPTER01NAME=Not a chapter file".toByteArray())
        fake.addFile("media", "Films/Noir/Laura.1944.chapters.txt", "CHAPTER01=00:00:00.000\nCHAPTER01NAME=Elsewhere\n".toByteArray())
        val vm = vm()
        advanceUntilIdle()
        assertEquals(listOf("Intro", "The heist", "intro"), vm.state.value.folderNames)
    }

    @Test
    fun `opening a film never opens a chapter file the folder does not list`() = runTest {
        // jcifs would create it: an open is a create for a missing file.
        val recording = RecordingGateway(fake)
        val vm = EditorViewModel(AppGraph(recording), route, this, player, io = StandardTestDispatcher(testScheduler), readEmbedded = { emptyList() })
        advanceUntilIdle()
        assertTrue(recording.opened.none { it.endsWith(".chapters.txt") })
        assertEquals(ChapterOrigin.NONE, vm.state.value.origin)
        assertNull(vm.state.value.problem)
    }

    @Test
    fun `a listed chapter file is read`() = runTest {
        fake.addFile("media", sidecar, "CHAPTER01=00:00:00.000\nCHAPTER01NAME=Listed\n".toByteArray())
        val recording = RecordingGateway(fake)
        val vm = EditorViewModel(AppGraph(recording), route, this, player, io = StandardTestDispatcher(testScheduler), readEmbedded = { emptyList() })
        advanceUntilIdle()
        assertTrue(recording.opened.contains(sidecar))
        assertEquals("Listed", vm.state.value.draft!!.marks[0].title)
    }

    @Test
    fun `an empty chapter file counts as none, so the chapters inside the film show and Save replaces it`() = runTest {
        fake.addFile("media", sidecar, ByteArray(0))
        val vm = vm(readEmbedded = { listOf(Chapter(0, "Opening")) })
        advanceUntilIdle()
        assertEquals(ChapterOrigin.EMBEDDED, vm.state.value.origin)
        assertFalse(vm.state.value.hasSidecar)
        assertTrue(vm.state.value.canSave)
        vm.save()
        advanceUntilIdle()
        assertEquals("CHAPTER01=00:00:00.000\nCHAPTER01NAME=Opening\n", text(sidecar))
    }
}
