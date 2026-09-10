package com.regolith.domain.artwork

import com.regolith.domain.smb.SmbEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The source order from design section 08, against the folder tree it draws. */
class ArtworkCandidatesTest {
    private fun file(name: String, size: Long = 100) = SmbEntry(name, isDirectory = false, sizeBytes = size, modifiedAtMs = 0)
    private fun dir(name: String) = SmbEntry(name, isDirectory = true, sizeBytes = 0, modifiedAtMs = 0)

    private fun names(list: List<ArtworkCandidates.Candidate>) = list.map { it.name to it.source }

    @Test fun `title folder - sidecar first, then basename image`() {
        val folder = listOf(file("Arrival.2016.2160p.mkv"), file("poster.jpg"), file("Arrival.2016.2160p.jpg"))
        assertEquals(
            listOf("poster.jpg" to ArtworkSource.SIDECAR, "Arrival.2016.2160p.jpg" to ArtworkSource.BASENAME),
            names(ArtworkCandidates.forFile("Arrival.2016.2160p.mkv", folder)),
        )
    }

    @Test fun `loose file in a collection folder ignores the collection poster`() {
        // Films/poster.jpg belongs to the Films tile; Hard.Boiled gets a frame grab.
        val films = listOf(file("poster.jpg"), dir("Arrival (2016)"), file("Hard.Boiled.1992.mp4"), file("paris-texas.mov"))
        assertTrue(ArtworkCandidates.forFile("Hard.Boiled.1992.mp4", films).isEmpty())
    }

    @Test fun `loose file still takes its basename image`() {
        val films = listOf(file("poster.jpg"), file("Hard.Boiled.1992.mp4"), file("hard.boiled.1992.PNG"), file("paris-texas.mov"))
        assertEquals(listOf("hard.boiled.1992.PNG" to ArtworkSource.BASENAME), names(ArtworkCandidates.forFile("Hard.Boiled.1992.mp4", films)))
    }

    @Test fun `sidecar stems and extensions are tried in the design's order, case-insensitively`() {
        val folder = listOf(file("Film.mkv"), file("Cover.PNG"), file("thumb.webp"), file("FOLDER.jpeg"))
        assertEquals(
            listOf("FOLDER.jpeg" to ArtworkSource.SIDECAR, "Cover.PNG" to ArtworkSource.SIDECAR, "thumb.webp" to ArtworkSource.SIDECAR),
            names(ArtworkCandidates.forFile("Film.mkv", folder)),
        )
    }

    @Test fun `images over 8 MB are skipped`() {
        val folder = listOf(file("Film.mkv"), file("poster.jpg", size = ArtworkCandidates.MAX_IMAGE_BYTES + 1), file("cover.jpg"))
        assertEquals(listOf("cover.jpg" to ArtworkSource.SIDECAR), names(ArtworkCandidates.forFile("Film.mkv", folder)))
    }

    @Test fun `non-image files never qualify`() {
        val folder = listOf(file("Film.mkv"), file("poster.txt"), file("Film.nfo"), file("Film.srt"))
        assertTrue(ArtworkCandidates.forFile("Film.mkv", folder).isEmpty())
    }

    @Test fun `folder candidates are its own sidecars only`() {
        val series = listOf(file("poster.jpg"), file("backdrop.jpg"), dir("Season 01"), file("Severance.jpg"))
        assertEquals(listOf("poster.jpg" to ArtworkSource.SIDECAR), names(ArtworkCandidates.forFolder(series)))
        assertTrue(ArtworkCandidates.forFolder(listOf(dir("A"), file("GH010423.MP4"))).isEmpty())
    }

    @Test fun `frame grab position is the midpoint of the runtime`() {
        assertEquals(30_000L, ArtworkCandidates.framePositionMs(60_000))
        assertEquals(0L, ArtworkCandidates.framePositionMs(0))
    }

    @Test fun `mosaic positions spread across the middle, one file or four`() {
        // Four videos: one frame each, all from the midpoint.
        assertEquals(listOf(30_000L), ArtworkCandidates.mosaicPositionsMs(60_000, 1))
        // One video filling all four cells: 20% to 80%, evenly.
        assertEquals(
            listOf(12_000L, 24_000L, 36_000L, 48_000L),
            ArtworkCandidates.mosaicPositionsMs(60_000, 4),
        )
        assertTrue(ArtworkCandidates.mosaicPositionsMs(0, 4).isEmpty())
        assertTrue(ArtworkCandidates.mosaicPositionsMs(60_000, 0).isEmpty())
    }
}
