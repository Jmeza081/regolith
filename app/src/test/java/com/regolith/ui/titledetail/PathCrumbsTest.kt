package com.regolith.ui.titledetail

import com.regolith.data.db.FolderEntity
import com.regolith.ui.components.PathCrumb
import org.junit.Assert.assertEquals
import org.junit.Test

/** Title Detail's Path line as steps that each open their folder. */
class PathCrumbsTest {

    private fun folder(id: Long, parentId: Long?, relPath: String, name: String) =
        FolderEntity(id = id, shareId = 1, parentId = parentId, relPath = relPath, name = name, fileCount = 0, byteCount = 0, lastListedAtMs = null)

    private val root = folder(1, null, "", "media")
    private val films = folder(2, 1, "Films", "Full-Length Films")
    private val deLora = folder(3, 2, "Films/De Lora", "De Lora")

    @Test
    fun `a share video's path is the share, then each folder down to its own`() {
        assertEquals(
            listOf(PathCrumb("Regolith", 1), PathCrumb("Full-Length Films", 2), PathCrumb("De Lora", 3)),
            shareCrumbs(listOf(root, films, deLora), shareName = "Regolith") { it },
        )
    }

    @Test
    fun `in spoof mode each folder reads as its tile does, and still opens the real one`() {
        assertEquals(
            listOf(PathCrumb("Regolith", 1), PathCrumb("made-up Full-Length Films", 2), PathCrumb("made-up De Lora", 3)),
            shareCrumbs(listOf(root, films, deLora), shareName = "Regolith") { "made-up $it" },
        )
    }

    @Test
    fun `a video at the top of its share has the share alone`() {
        assertEquals(listOf(PathCrumb("media", 1)), shareCrumbs(listOf(root), shareName = "media") { it })
    }

    @Test
    fun `a chain cut short still starts at the share, which then cannot be opened`() {
        assertEquals(
            listOf(PathCrumb("media", null), PathCrumb("De Lora", 3)),
            shareCrumbs(listOf(deLora), shareName = "media") { it },
        )
    }

    @Test
    fun `a phone video's album opens, the phone's folders above it do not`() {
        assertEquals(listOf(PathCrumb("DCIM", null), PathCrumb("Camera", 7)), phoneCrumbs("DCIM/Camera", folderId = 7))
        assertEquals(listOf(PathCrumb("SD card · Movies", 8)), phoneCrumbs("SD card · Movies", folderId = 8))
        assertEquals(listOf(PathCrumb("Internal storage", 9)), phoneCrumbs("Internal storage", folderId = 9))
    }
}
