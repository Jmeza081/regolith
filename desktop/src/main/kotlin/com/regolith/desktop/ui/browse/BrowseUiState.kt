package com.regolith.desktop.ui.browse

import com.regolith.desktop.ui.Problem
import com.regolith.domain.smb.SmbEntry

/** Everything the Browse screen draws. */
data class BrowseUiState(
    /** The folder's name, or the share's at its root. */
    val title: String,
    /** `localhost:1445/media/Films`: where this is, as small print. */
    val location: String,
    val loading: Boolean = true,
    val rows: List<BrowseRow> = emptyList(),
    val problem: Problem? = null,
)

/** A folder, or a film with whether it already has a chapter file and which image to show. */
data class BrowseRow(
    val entry: SmbEntry,
    val hasChapters: Boolean,
    /**
     * The image drawn beside the row: a film's, from the same listing, or a
     * folder's poster, found once Browse has looked inside it. Null draws the
     * folder icon, or a film's unmatched look.
     */
    val image: RowImage? = null,
) {
    val isFolder: Boolean get() = entry.isDirectory
}

/** An image file on the share: its path from the share's root, and its size, which is part of the cache key. */
data class RowImage(val relPath: String, val sizeBytes: Long)
