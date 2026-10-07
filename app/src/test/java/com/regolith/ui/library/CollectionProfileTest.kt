package com.regolith.ui.library

import com.regolith.domain.artwork.ArtworkKind
import com.regolith.domain.artwork.ArtworkOwner
import com.regolith.domain.artwork.ArtworkRequest
import com.regolith.domain.library.MomentOrder
import com.regolith.domain.library.MomentSort
import com.regolith.domain.library.SortDirection
import com.regolith.domain.playback.ChapterMatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** Only the last stop down a branch is a profile, and its stats say what is on its wall. */
class CollectionProfileTest {

    private val poster = ArtworkRequest(ArtworkOwner.Folder(7), ArtworkKind.POSTER, animated = true)

    private fun video(id: Long, name: String, durationMs: Long? = 60_000, sizeBytes: Long = 1_000) = LibraryTile.Title(
        fileId = id, name = name, resolutionLabel = "", unwatched = false, fileName = "$name.mp4",
        progress = null, meta = "", artwork = ArtworkRequest(ArtworkOwner.File(id), ArtworkKind.POSTER),
        addedAtMs = 0, sizeBytes = sizeBytes, durationMs = durationMs, height = null,
    )

    private fun collection(id: Long) = LibraryTile.Collection(
        folderId = id, name = "Season $id", fileCount = 3, resolutionLabel = "",
        artwork = ArtworkRequest(ArtworkOwner.Folder(id), ArtworkKind.POSTER),
        addedAtMs = 0, sizeBytes = 0, durationMs = null, height = null,
    )

    private fun mark(fileId: Long, startMs: Long, title: String) =
        ChapterMatch(fileId = fileId, startMs = startMs, title = title, fileName = "", fileTitle = null, shareId = 1, fileRelPath = "")

    private fun profileOf(tiles: List<LibraryTile>, completed: Set<Long> = emptySet(), marks: List<ChapterMatch> = emptyList()) =
        collectionProfile(tiles, parentName = "Home videos", poster = poster, completed = completed, marks = marks)

    @Test
    fun `a wall holding a collection stays a wall`() {
        assertNull(profileOf(listOf(video(1, "Cake"), collection(2))))
        assertNull(profileOf(listOf(collection(2), collection(3))))
    }

    @Test
    fun `a wall of videos is a profile, even of one`() {
        val profile = profileOf(listOf(video(1, "First steps")))
        assertNotNull(profile)
        assertEquals(1, profile?.videoCount)
        assertEquals("Home videos", profile?.parentName)
    }

    @Test
    fun `nothing on the wall is no profile`() {
        assertNull(profileOf(emptyList()))
    }

    @Test
    fun `stats add up the wall and count what was played to the end`() {
        val profile = profileOf(
            listOf(video(1, "Dock", durationMs = 724_000, sizeBytes = 3_900), video(2, "Swing", durationMs = 530_000, sizeBytes = 2_800)),
            completed = setOf(2, 99),
        )!!
        assertEquals(2, profile.videoCount)
        assertEquals(1_254_000L, profile.runtimeMs)
        assertEquals(6_700L, profile.sizeBytes)
        assertEquals(1, profile.watchedCount)
    }

    @Test
    fun `one video of unknown length leaves the runtime out rather than short`() {
        val profile = profileOf(listOf(video(1, "Dock", durationMs = 724_000), video(2, "Swing", durationMs = null)))!!
        assertNull(profile.runtimeMs)
    }

    @Test
    fun `the light is the poster held still`() {
        val profile = profileOf(listOf(video(1, "Dock")))!!
        assertEquals(poster, profile.poster)
        assertFalse(profile.light.animated)
        assertEquals(poster.owner, profile.light.owner)
    }

    @Test
    fun `moments keep to the wall's videos and name them as their tiles do`() {
        val profile = profileOf(
            listOf(video(1, "Rope swing"), video(2, "Storm")),
            marks = listOf(mark(1, 192_000, "First jump"), mark(3, 0, "Hidden video's mark"), mark(2, 100_000, "The storm rolls in")),
        )!!
        assertEquals(
            listOf(CollectionMoment(1, 192_000, "First jump", "Rope swing"), CollectionMoment(2, 100_000, "The storm rolls in", "Storm")),
            profile.moments,
        )
    }

    // Named on different days: the backflip last, the storm first.
    private val moments = listOf(
        CollectionMoment(1, 408_000, "The backflip", "Rope swing", namedAtMs = 3_000),
        CollectionMoment(1, 192_000, "First jump", "Rope swing", namedAtMs = 2_000),
        CollectionMoment(2, 100_000, "The storm rolls in", "Storm", namedAtMs = 1_000),
    )
    private val stormFirst = listOf(video(2, "Storm"), video(1, "Rope swing"))

    @Test
    fun `by video, moments follow the wall's order, then time`() {
        assertEquals(listOf("The storm rolls in", "First jump", "The backflip"), moments.inOrder(MomentOrder(), stormFirst).map { it.title })
    }

    @Test
    fun `reversed by video, the videos turn round but each still plays forwards`() {
        val reversed = MomentOrder(MomentSort.VIDEO, SortDirection.DESCENDING)
        assertEquals(listOf("First jump", "The backflip", "The storm rolls in"), moments.inOrder(reversed, stormFirst).map { it.title })
    }

    @Test
    fun `by name, a moment's own name decides, not its video's`() {
        assertEquals(listOf("First jump", "The backflip", "The storm rolls in"), moments.inOrder(MomentOrder(MomentSort.NAME), stormFirst).map { it.title })
        val zToA = MomentOrder(MomentSort.NAME, SortDirection.DESCENDING)
        assertEquals(listOf("The storm rolls in", "The backflip", "First jump"), moments.inOrder(zToA, stormFirst).map { it.title })
    }

    @Test
    fun `by date named, the newest name comes first`() {
        assertEquals(listOf("The backflip", "First jump", "The storm rolls in"), moments.inOrder(MomentOrder(MomentSort.DATE_NAMED), stormFirst).map { it.title })
    }

    @Test
    fun `a moment carries the day it was named from its mark`() {
        val profile = profileOf(listOf(video(1, "Rope swing")), marks = listOf(mark(1, 192_000, "First jump").copy(namedAtMs = 42)))!!
        assertEquals(42L, profile.moments.single().namedAtMs)
    }
}
