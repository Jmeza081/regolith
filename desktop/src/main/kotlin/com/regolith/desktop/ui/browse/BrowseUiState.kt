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
    /** The image drawn beside a film, from the same listing; null when none applies. */
    val image: SmbEntry? = null,
) {
    val isFolder: Boolean get() = entry.isDirectory
}
