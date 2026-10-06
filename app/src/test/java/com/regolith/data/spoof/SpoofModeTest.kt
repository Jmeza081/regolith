package com.regolith.data.spoof

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.regolith.data.db.FolderEntity
import com.regolith.data.db.MediaFileEntity
import com.regolith.domain.artwork.ArtworkKind
import com.regolith.domain.artwork.ArtworkOwner
import com.regolith.domain.artwork.ArtworkRequest
import com.regolith.domain.fileops.ReadOnlySource
import com.regolith.testing.testSpoofMode
import com.regolith.ui.util.FileVerbs
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Spoof mode changes what is shown and nothing a file is found by. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class SpoofModeTest {

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    private val video = MediaFileEntity(
        id = 7, shareId = 1, folderId = 3, relPath = "Films/Arrival (2016)/Arrival.2016.2160p.mkv", name = "Arrival.2016.2160p.mkv", ext = "mkv",
        sizeBytes = 1, modifiedAtMs = 1, durationMs = 60_000, missing = false, addedAtMs = 1, lastSeenAtMs = 1,
        titleParsed = "Arrival", year = 2016,
    )

    @Test
    fun `off, it is null and a flow passes through untouched`() = runTest {
        val mode = testSpoofMode(context)
        assertNull(mode.current)
        assertEquals(listOf(video), flowOf(listOf(video)).spoofed(mode) { files(it) }.first())
    }

    @Test
    fun `on, a video is renamed and still found by its real id and path`() = runTest {
        val mode = testSpoofMode(context, on = true)
        val spoof = assertNotNullAndGet(mode.current)
        val shown = flowOf(listOf(video)).spoofed(mode) { files(it) }.first().single()
        assertEquals(video.id, shown.id)
        assertEquals(video.relPath, shown.relPath)
        assertEquals(2016, shown.year)
        assertNotEquals(video.name, shown.name)
        assertEquals(".mkv", shown.name.substring(shown.name.lastIndexOf('.')))
        // The tile's title and the file's name are the same made-up words.
        assertEquals(shown.name.substringBeforeLast('.'), shown.titleParsed)
        assertEquals(spoof.file(video), shown)
    }

    @Test
    fun `a folder keeps its path, and its name is the one its paths use`() {
        val spoof = Spoof(salt = 99)
        val folder = FolderEntity(id = 3, shareId = 1, parentId = 2, relPath = "Home videos/Lake house 2024", name = "Lake house 2024", fileCount = 0, byteCount = 0, lastListedAtMs = 1)
        val shown = spoof.folder(folder)
        assertEquals(folder.relPath, shown.relPath)
        assertEquals(spoof.path(folder.relPath).substringAfterLast('/'), shown.name)
    }

    @Test
    fun `a stand-in photo is sized for the picture it replaces and keyed by id`() {
        val spoof = Spoof(salt = 99)
        val poster = spoof.image(ArtworkRequest(ArtworkOwner.File(7), ArtworkKind.POSTER))
        val thumb = spoof.image(ArtworkRequest(ArtworkOwner.File(7), ArtworkKind.THUMB))
        assertEquals(200 to 300, poster.width to poster.height)
        assertEquals(320 to 180, thumb.width to thumb.height)
        assertEquals(poster.seed, thumb.seed)
        assertNotEquals(poster.seed, spoof.image(ArtworkRequest(ArtworkOwner.File(8), ArtworkKind.POSTER)).seed)
    }

    @Test
    fun `while it is on, nothing can be changed`() {
        val verbs = FileVerbs.of(itemCount = 1, shares = 1, readOnly = ReadOnlySource.SPOOF)
        assertFalse(verbs.canMove || verbs.canRename || verbs.canDelete)
        assertEquals("Nothing can be changed while spoof mode is on", verbs.hint)
    }

    private fun <T> assertNotNullAndGet(value: T?): T {
        assertNotNull(value)
        return value!!
    }
}
