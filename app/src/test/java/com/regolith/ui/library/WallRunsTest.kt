package com.regolith.ui.library

import com.regolith.domain.artwork.ArtworkKind
import com.regolith.domain.artwork.ArtworkOwner
import com.regolith.domain.artwork.ArtworkRequest
import org.junit.Assert.assertEquals
import org.junit.Test

/** How a wall split into collections and mixes is drawn: each run under its heading. */
class WallRunsTest {
    private fun folder(id: Long, mix: Boolean = false) = LibraryTile.Collection(
        folderId = id, name = "F$id", fileCount = 1, resolutionLabel = "",
        artwork = ArtworkRequest(ArtworkOwner.Folder(id), ArtworkKind.POSTER),
        addedAtMs = 0, sizeBytes = 0, durationMs = null, height = null, mix = mix,
    )

    @Test fun `an unsplit wall is one run with no heading`() {
        val tiles = listOf(folder(1), folder(2))
        assertEquals(listOf(tiles to null), wallRuns(tiles, mixStart = null))
    }

    @Test fun `a split wall is the collections, then the mixes, each under its heading`() {
        val tiles = listOf(folder(1), folder(2), folder(3, mix = true))
        assertEquals(
            listOf(listOf(folder(1), folder(2)) to WallSection.COLLECTIONS, listOf(folder(3, mix = true)) to WallSection.MIXES),
            wallRuns(tiles, mixStart = 2),
        )
    }
}
