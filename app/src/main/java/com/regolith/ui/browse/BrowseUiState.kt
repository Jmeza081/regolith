package com.regolith.ui.browse

import com.regolith.domain.artwork.ArtworkKind
import com.regolith.domain.artwork.ArtworkOwner
import com.regolith.domain.artwork.ArtworkRequest
import com.regolith.domain.library.ViewMode
import com.regolith.ui.util.SelectionUiState

/** One entry on the Browse screen: a row at the root, a tile inside a folder. */
sealed interface BrowseRow {
    val testTag: String

    /** Root level only: an enabled share on a server. */
    data class ShareRow(val shareId: Long, val name: String, val serverName: String, val freeBytes: Long?) : BrowseRow {
        override val testTag get() = "browse_share_$shareId"
    }

    data class FolderRow(
        val folderId: Long,
        val name: String,
        val fileCount: Int,
        val byteCount: Long,
        val shareId: Long = 0,
        /** `/`-joined path inside the share; what a download pick is keyed on. */
        val relPath: String = "",
        /** False when never listed, so a selection knows its counts are a floor. */
        val listed: Boolean = true,
    ) : BrowseRow {
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
        val shareId: Long = 0,
        /** The folder holding it, so a selection can tell if an ancestor is picked. */
        val folderRelPath: String = "",
    ) : BrowseRow {
        override val testTag get() = "browse_file_$fileId"
        val artwork get() = ArtworkRequest(ArtworkOwner.File(fileId), ArtworkKind.THUMB)

        /** 0..1 watched, for the bar along the bottom of a tile; null when never started. */
        val fraction: Float?
            get() = if (progressMs != null && durationMs != null && durationMs > 0) (progressMs.toFloat() / durationMs).coerceIn(0f, 1f) else null
    }
}

/**
 * One line of the share tree beside the folder list on a wide window: a
 * share, or one of its top-level folders. Two levels only — the tree is a
 * way back to the top, not a second file browser.
 */
data class TreeNode(
    val folderId: Long,
    val name: String,
    /** 0 = the share itself, 1 = a folder directly under it. */
    val depth: Int,
    /** Playable files directly inside, drawn at the end of the line. */
    val fileCount: Int,
    val isShare: Boolean,
) {
    val testTag get() = "browse_tree_$folderId"
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
    /** Rows or tiles. Remembered across launches, separately from Library's. */
    val viewMode: ViewMode = ViewMode.ROWS,
    /** Every enabled share and its top-level folders, for the wide window's tree. Empty elsewhere. */
    val tree: List<TreeNode> = emptyList(),
    /** Which tree line is the folder on screen. */
    val currentFolderId: Long? = null,
    /** Non-null while a multi-selection is running (the contextual bar is up). */
    val selection: SelectionUiState? = null,
)
