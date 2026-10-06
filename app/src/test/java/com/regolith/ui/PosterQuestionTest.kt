package com.regolith.ui

import com.regolith.domain.artwork.ArtworkKind
import com.regolith.domain.artwork.ArtworkOwner
import com.regolith.ui.components.posterQuestion
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What the question says when a poster is picked for a folder that has a
 * picture of its own already (P19): the names involved, spelled out, so the
 * choice is about something concrete.
 */
class PosterQuestionTest {

    private fun question(vararg existing: Pair<String, Long>, kept: Map<String, String> = emptyMap()) = posterQuestion(
        folderId = 12,
        folderName = "Heat (1995)",
        serverName = "TOWER",
        pickedUri = "content://media/picker/0/42",
        existing = existing.toList(),
        keptNames = kept,
    )

    @Test
    fun `one picture there is named, along with what renaming it would call it`() {
        val q = question("folder.jpg" to 1_200_000L, kept = mapOf("folder.jpg" to "folder (1).jpg"))
        assertEquals("folder.jpg is there now", q.subtitle)
        assertEquals("Rename the old one", q.renameLabel)
        assertEquals("folder.jpg becomes folder (1).jpg", q.renameNote)
        assertEquals("Replace it", q.replaceLabel)
        assertEquals("folder.jpg is deleted from TOWER", q.replaceNote)
        assertEquals("Saved as poster.jpg", q.pickedNote)
    }

    @Test
    fun `several pictures are counted rather than listed in the lines`() {
        val q = question("poster.png" to 10L, "folder.jpg" to 20L, kept = mapOf("poster.png" to "poster (1).png", "folder.jpg" to "folder (1).jpg"))
        assertEquals("2 pictures are there now", q.subtitle)
        assertEquals("Rename the old ones", q.renameLabel)
        assertEquals("Replace them", q.replaceLabel)
        assertEquals("They're deleted from TOWER", q.replaceNote)
        assertEquals(listOf("poster (1).png", "folder (1).jpg"), q.existing.map { it.keptName })
    }

    @Test
    fun `a gif that moves says it is saved as poster gif`() {
        val q = posterQuestion(
            folderId = 12,
            folderName = "Films",
            serverName = "TOWER",
            pickedUri = "content://media/picker/0/43",
            existing = listOf("poster.jpg" to 1L),
            keptNames = mapOf("poster.jpg" to "poster (1).jpg"),
            pickedName = "poster.gif",
        )
        assertEquals("Saved as poster.gif", q.pickedNote)
        assertEquals("poster.jpg becomes poster (1).jpg", q.renameNote)
    }

    @Test
    fun `the folder's current picture is the one the app already shows for it`() {
        val q = question("folder.jpg" to 1L)
        assertEquals(ArtworkOwner.Folder(12), q.current.owner)
        assertEquals(ArtworkKind.POSTER, q.current.kind)
        assertEquals("content://media/picker/0/42", q.picked.uri)
    }
}
