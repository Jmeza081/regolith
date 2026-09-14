package com.regolith.domain.library

import org.junit.Assert.assertEquals
import org.junit.Test

/** The tree from design section 08, folder by folder. */
class FolderClassifierTest {
    private fun kind(name: String, dirs: List<String> = emptyList(), files: List<String> = emptyList(), root: Boolean = false) =
        FolderClassifier.classify(name, root, dirs + files) { it in dirs }

    @Test fun `the share root`() {
        assertEquals(FolderKind.ROOT, kind("media", dirs = listOf("Films", "Series"), root = true))
    }

    @Test fun `Films is a collection of title folders and loose files`() {
        assertEquals(FolderKind.COLLECTION, kind("Films", dirs = listOf("Arrival (2016)"), files = listOf("poster.jpg", "Hard.Boiled.1992.mp4")))
    }

    @Test fun `Arrival (2016) is a title`() {
        assertEquals(FolderKind.TITLE, kind("Arrival (2016)", files = listOf("Arrival.2016.2160p.mkv", "poster.jpg", "Arrival.2016.2160p.jpg")))
    }

    @Test fun `a folder with one video and no year is still a title`() {
        assertEquals(FolderKind.TITLE, kind("The Thing", files = listOf("The.Thing.1982.mkv", "The.Thing.1982.srt")))
    }

    @Test fun `Home videos with many loose files is a collection`() {
        assertEquals(FolderKind.COLLECTION, kind("Home videos", files = listOf("GH010423.MP4", "GH010424.MP4")))
    }

    @Test fun `Series, Severance, Season 01`() {
        assertEquals(FolderKind.COLLECTION, kind("Series", dirs = listOf("Severance")))
        assertEquals(FolderKind.SHOW, kind("Severance", dirs = listOf("Season 01"), files = listOf("poster.jpg")))
        assertEquals(FolderKind.SEASON, kind("Season 01", files = listOf("Severance.S01E01.mkv")))
        assertEquals(FolderKind.SHOW, kind("Severance", files = listOf("Severance.S01E01.mkv", "Severance.S01E02.mkv")))
    }

    @Test fun `nothing playable is plain`() {
        assertEquals(FolderKind.PLAIN, kind("extras", files = listOf("notes.txt")))
        assertEquals(FolderKind.PLAIN, kind("empty"))
    }
}
