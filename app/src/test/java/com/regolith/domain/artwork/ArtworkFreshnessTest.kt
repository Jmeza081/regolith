package com.regolith.domain.artwork

import com.regolith.domain.artwork.ArtworkFreshness.NO_IMAGES
import com.regolith.domain.smb.SmbEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When a cached picture no longer matches the share it came from. The
 * question the owner asked, "I replaced folder.jpg with a different picture
 * of the same name; does the app notice?", is the first test here.
 */
class ArtworkFreshnessTest {
    private fun image(name: String, size: Long = 100_000, modified: Long = 1_000_000) = SmbEntry(name, isDirectory = false, sizeBytes = size, modifiedAtMs = modified)
    private fun video(name: String) = SmbEntry(name, isDirectory = false, sizeBytes = 1_000_000_000, modifiedAtMs = 1)

    private fun folderStamp(listing: List<SmbEntry>) = ArtworkFreshness.stamp(ArtworkCandidates.forFolder(listing), listing)
    private fun fileStamp(name: String, listing: List<SmbEntry>) = ArtworkFreshness.stamp(ArtworkCandidates.forFile(name, listing), listing)

    // ── The folder's own picture ────────────────────────────────────────

    @Test
    fun `folder_jpg replaced with a different picture of the same name is noticed`() {
        val before = folderStamp(listOf(image("folder.jpg", size = 90_210, modified = 1_000)))
        val after = folderStamp(listOf(image("folder.jpg", size = 131_072, modified = 9_000)))
        assertTrue(ArtworkFreshness.isStale(before, after, ArtworkSource.SIDECAR))
    }

    @Test
    fun `a replacement of the same size is still noticed by its time`() {
        val before = folderStamp(listOf(image("folder.jpg", size = 90_210, modified = 1_000)))
        val after = folderStamp(listOf(image("folder.jpg", size = 90_210, modified = 5_000)))
        assertTrue(ArtworkFreshness.isStale(before, after, ArtworkSource.SIDECAR))
    }

    @Test
    fun `an untouched picture stays, listing after listing`() {
        val listing = listOf(image("folder.jpg"), video("Heat.1995.mkv"))
        assertFalse(ArtworkFreshness.isStale(folderStamp(listing), folderStamp(listing), ArtworkSource.SIDECAR))
    }

    @Test
    fun `a poster_jpg added beside folder_jpg wins, so the folder changes`() {
        val before = folderStamp(listOf(image("folder.jpg")))
        val after = folderStamp(listOf(image("folder.jpg"), image("poster.jpg")))
        assertTrue(ArtworkFreshness.isStale(before, after, ArtworkSource.SIDECAR))
    }

    @Test
    fun `a picture removed from the share goes, and the folder falls back to its mosaic`() {
        val before = folderStamp(listOf(image("folder.jpg")))
        assertTrue(ArtworkFreshness.isStale(before, folderStamp(emptyList()), ArtworkSource.SIDECAR))
    }

    @Test
    fun `a mosaic gives way the moment a folder gets a picture of its own`() {
        assertTrue(ArtworkFreshness.isStale(NO_IMAGES, folderStamp(listOf(image("cover.png"))), ArtworkSource.MOSAIC))
        assertFalse(ArtworkFreshness.isStale(NO_IMAGES, folderStamp(emptyList()), ArtworkSource.MOSAIC))
    }

    @Test
    fun `an image too big to use does not count as the folder's picture`() {
        val huge = image("folder.jpg", size = ArtworkCandidates.MAX_IMAGE_BYTES + 1)
        assertEquals(NO_IMAGES, folderStamp(listOf(huge)))
    }

    // ── A film's picture ────────────────────────────────────────────────

    @Test
    fun `a basename image added after the frame was grabbed replaces the frame`() {
        val before = fileStamp("Arrival.2016.mkv", listOf(video("Arrival.2016.mkv"), video("Heat.1995.mkv")))
        val after = fileStamp("Arrival.2016.mkv", listOf(video("Arrival.2016.mkv"), video("Heat.1995.mkv"), image("Arrival.2016.jpg")))
        assertEquals(NO_IMAGES, before)
        assertTrue(ArtworkFreshness.isStale(before, after, ArtworkSource.FRAMEGRAB))
    }

    @Test
    fun `a broken image beside a film does not make its frame be grabbed again every listing`() {
        // The image was tried when the picture was made, would not decode, and
        // the frame was grabbed instead. Its stamp includes the broken image,
        // so the same broken image the next time is not a change.
        val listing = listOf(video("Arrival.2016.mkv"), video("Heat.1995.mkv"), image("Arrival.2016.jpg"))
        val stamp = fileStamp("Arrival.2016.mkv", listing)
        assertFalse(ArtworkFreshness.isStale(stamp, fileStamp("Arrival.2016.mkv", listing), ArtworkSource.FRAMEGRAB))
    }

    @Test
    fun `a folder's poster_jpg is a film's only when the film is alone there`() {
        val alone = listOf(video("Arrival.2016.mkv"), image("poster.jpg"))
        val shared = listOf(video("Arrival.2016.mkv"), video("Heat.1995.mkv"), image("poster.jpg"))
        assertTrue(fileStamp("Arrival.2016.mkv", alone).startsWith("poster.jpg"))
        assertEquals(NO_IMAGES, fileStamp("Arrival.2016.mkv", shared))
    }

    // ── Rows from before stamps ─────────────────────────────────────────

    @Test
    fun `a picture read off the share before stamps existed is read once more`() {
        assertTrue(ArtworkFreshness.isStale(null, folderStamp(listOf(image("folder.jpg"))), ArtworkSource.SIDECAR))
        assertTrue(ArtworkFreshness.isStale(null, folderStamp(listOf(image("Arrival.jpg"))), ArtworkSource.BASENAME))
    }

    @Test
    fun `an old generated picture goes only if the share now has an image for it`() {
        assertFalse(ArtworkFreshness.isStale(null, NO_IMAGES, ArtworkSource.FRAMEGRAB))
        assertTrue(ArtworkFreshness.isStale(null, folderStamp(listOf(image("folder.jpg"))), ArtworkSource.MOSAIC))
    }

    @Test
    fun `an old placeholder is left to expire on its own`() {
        assertFalse(ArtworkFreshness.isStale(null, folderStamp(listOf(image("folder.jpg"))), ArtworkSource.PLACEHOLDER))
    }

    // ── One listing, every owner in it ──────────────────────────────────

    @Test
    fun `one listing checks the folder and each of its films against its own images`() {
        val before = listOf(image("folder.jpg", size = 1), video("Arrival.2016.mkv"), video("Heat.1995.mkv"))
        val after = listOf(image("folder.jpg", size = 2), video("Arrival.2016.mkv"), video("Heat.1995.mkv"), image("Heat.1995.jpg"))
        val cached = listOf(
            CachedPicture(ArtworkOwner.Folder(3), ArtworkSource.SIDECAR, folderStamp(before)),
            CachedPicture(ArtworkOwner.File(10), ArtworkSource.FRAMEGRAB, fileStamp("Arrival.2016.mkv", before)),
            CachedPicture(ArtworkOwner.File(11), ArtworkSource.FRAMEGRAB, fileStamp("Heat.1995.mkv", before)),
        )
        val stale = ArtworkFreshness.staleOwners(3, listOf(10L to "Arrival.2016.mkv", 11L to "Heat.1995.mkv"), after, cached)
        assertEquals(listOf(ArtworkOwner.Folder(3), ArtworkOwner.File(11)), stale)
    }

    @Test
    fun `an owner with no picture yet has nothing to throw away`() {
        val listing = listOf(image("folder.jpg"), video("Arrival.2016.mkv"))
        assertTrue(ArtworkFreshness.staleOwners(3, listOf(10L to "Arrival.2016.mkv"), listing, emptyList()).isEmpty())
    }

    @Test
    fun `any one kind out of date is enough to remake the owner`() {
        // The poster and the thumb are made together; a thumb made from the
        // old picture beside a poster from the new would be two pictures.
        val listing = listOf(image("folder.jpg", size = 2))
        val cached = listOf(
            CachedPicture(ArtworkOwner.Folder(3), ArtworkSource.SIDECAR, folderStamp(listing)),
            CachedPicture(ArtworkOwner.Folder(3), ArtworkSource.SIDECAR, folderStamp(listOf(image("folder.jpg", size = 1)))),
        )
        assertEquals(listOf(ArtworkOwner.Folder(3)), ArtworkFreshness.staleOwners(3, emptyList(), listing, cached))
    }
}
