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

/** A folder, or a film with whether it already has a chapter file. */
data class BrowseRow(
    val entry: SmbEntry,
    val hasChapters: Boolean,
) {
    val isFolder: Boolean get() = entry.isDirectory
}
