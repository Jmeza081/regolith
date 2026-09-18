package com.regolith.ui.browse

import com.regolith.domain.artwork.ArtworkKind
import com.regolith.domain.artwork.ArtworkOwner
import com.regolith.domain.artwork.ArtworkRequest
import com.regolith.domain.library.ViewMode
import com.regolith.domain.fileops.FileOpTarget
import com.regolith.ui.components.MoveSheetState
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
    // --- Managing things on the share (P12, folders added in P13). A
    // picked folder is now a first-class target: moved and deleted whole,
    // subtree and all.
    /** Move and Delete need at least one thing picked, of either kind. */
    val canManage: Boolean = false,
    /** Rename needs exactly one thing picked — a file or a folder. */
    val canRename: Boolean = false,
    /** Why a verb is off, for the bar's detail line. */
    val selectionHint: String? = null,
    val renaming: RenameTarget? = null,
    val confirmingDelete: DeleteTarget? = null,
    val moveSheet: MoveSheetState? = null,
    /** The folder a new one would be made in, while the name is being typed. */
    val newFolderIn: Long? = null,
    /** The folder this screen was showing has been deleted; the screen should pop. */
    val gone: Boolean = false,
    /** One-shot line for the snackbar, with an Undo when the move can be walked back. */
    val fileOpMessage: FileOpMessage? = null,
)

/** The one thing a rename is about — a file, or a folder. */
data class RenameTarget(val target: FileOpTarget, val name: String) {
    val isFolder: Boolean get() = target.isFolder
}

/**
 * What a delete would take, named and totalled, so the dialog can say it.
 *
 * [videoCount] and [sizeLabel] count THROUGH picked folders — everything
 * the recursive delete would reach that the app knows about — while
 * [folderCount] counts the folders actually picked. The dialog needs both:
 * one is what is going, the other is what is being tapped.
 */
data class DeleteTarget(
    val targets: List<FileOpTarget>,
    val names: List<String>,
    val sizeLabel: String,
    val videoCount: Int,
    val folderCount: Int,
)

/**
 * What just happened, for the snackbar.
 *
 * [undo] is only ever offered for a move, and only when every file made it:
 * the inverse of a move is the same single rename back, which is why it can
 * be offered at all. A delete is a real unlink and has none.
 */
data class FileOpMessage(
    val text: String,
    val undo: UndoMove? = null,
    /** Something did not go through, so it is shown for longer and carries no Undo. */
    val failed: Boolean = false,
)

/** Put these back where they came from. */
data class UndoMove(val targets: List<FileOpTarget>, val backToFolderId: Long)
