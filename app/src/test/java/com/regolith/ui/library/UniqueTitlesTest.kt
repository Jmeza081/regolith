package com.regolith.ui.library

import com.regolith.domain.artwork.ArtworkKind
import com.regolith.domain.artwork.ArtworkOwner
import com.regolith.domain.artwork.ArtworkRequest
import org.junit.Assert.assertEquals
import org.junit.Test

/** Every video on a wall reads as its own: no two tiles wear the same name. */
class UniqueTitlesTest {

    private fun title(id: Long, name: String, fileName: String) = LibraryTile.Title(
        fileId = id, name = name, resolutionLabel = "", unwatched = false, fileName = fileName,
        progress = null, meta = "", artwork = ArtworkRequest(ArtworkOwner.File(id), ArtworkKind.POSTER),
        addedAtMs = 0, sizeBytes = 0, durationMs = null, height = null,
    )

    @Test
    fun `two videos that parse alike are named by their files`() {
        val wall = listOf(
            title(1, "Heat (1995)", "Heat.1995.1080p.mkv"),
            title(2, "Heat (1995)", "Heat.1995.2160p.mkv"),
            title(3, "Sicario (2015)", "Sicario.2015.mkv"),
        ).withUniqueTitles()
        assertEquals(listOf("Heat.1995.1080p", "Heat.1995.2160p", "Sicario (2015)"), wall.map { it.name })
    }

    @Test
    fun `a wall with nothing alike is left as it is`() {
        val wall = listOf(title(1, "Hawaii 2019 - day 1", "Hawaii 2019 - day 1.mp4"), title(2, "Hawaii 2019 - day 2", "Hawaii 2019 - day 2.mp4"))
        assertEquals(wall, wall.withUniqueTitles())
    }
}
