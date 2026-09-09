package com.regolith.ui.browse

import com.regolith.domain.artwork.ArtworkKind
import com.regolith.domain.artwork.ArtworkOwner
import com.regolith.domain.artwork.ArtworkRequest

/** One entry on the Browse screen: a row at the root, a tile inside a folder. */
sealed interface BrowseRow {
    val testTag: String

    /** Root level only: an enabled share on a server. */
    data class ShareRow(val shareId: Long, val name: String, val serverName: String, val freeBytes: Long?) : BrowseRow {
        override val testTag get() = "browse_share_$shareId"
    }

    data class FolderRow(val folderId: Long, val name: String, val fileCount: Int, val byteCount: Long) : BrowseRow {
        override val testTag get() = "browse_folder_$folderId"
        val artwork get() = ArtworkRequest(ArtworkOwner.Folder(folderId), ArtworkKind.THUMB)
    }

    data class FileRow(
        val fileId: Long,
        val name: String,
        val ext: String,
        val sizeBytes: Long,
        val progressMs: Long?,
        val durationMs: Long?,
        /** "4K", "1080p" once the file has been opened; empty until then. */
        val resolutionLabel: String,
    ) : BrowseRow {
        override val testTag get() = "browse_file_$fileId"
        val artwork get() = ArtworkRequest(ArtworkOwner.File(fileId), ArtworkKind.THUMB)
    }
}

data class BrowseUiState(
    /** "Browse" at the root, else the share name and path, e.g. "media / Films". */
    val title: String = "Browse",
    val breadcrumb: String? = null,
    val rows: List<BrowseRow> = emptyList(),
    val loaded: Boolean = false,
    val refreshing: Boolean = false,
    /** Share unreachable: show what is saved, with this message on top. */
    val offlineMessage: String? = null,
    /** Root with no enabled share anywhere. */
    val noSource: Boolean = false,
)
