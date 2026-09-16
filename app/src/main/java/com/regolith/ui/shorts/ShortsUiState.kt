package com.regolith.ui.shorts

/** One clip in the feed. Everything the page draws, and nothing it does not. */
data class ShortItem(
    val fileId: Long,
    /** The filename without its extension; a short rarely has a parsed title. */
    val name: String,
    /** Where it lives, for the line under the name: "Home videos". */
    val folderLabel: String,
    /** "0:18 · 1080×1920". */
    val meta: String,
    /** What Locate opens, and what the folder picker filters on. */
    val folderId: Long,
    val onDevice: Boolean = false,
) {
    val testTag get() = "shorts_page_$fileId"
}

/**
 * A folder offered in the "Play from" sheet.
 *
 * Only folders that actually hold shorts are offered, with their counts —
 * a picker listing every folder on the share would mostly be dead ends.
 */
data class ShortsFolder(val id: Long, val label: String, val count: Int) {
    val testTag get() = "shorts_folder_$id"
}

/**
 * The Shorts feed.
 *
 * [measuringLine] is flattened to a string here rather than carrying the
 * artwork walk's own status type, for the same reason [com.regolith.ui.home.HomeUiState]
 * flattens `refreshLine`: the UI does not reach into `data/`.
 *
 * An empty feed has two very different meanings and the screen must be able
 * to tell them apart — [loaded] with no [items] and no [measuringLine] is
 * "there are no vertical clips here", while a [measuringLine] is "we have
 * not finished looking". Showing the same words for both is how an empty
 * feed comes to look broken.
 */
data class ShortsUiState(
    val loaded: Boolean = false,
    /** What the pager shows: filtered to [folderId] and ordered by [shuffled]. */
    val items: List<ShortItem> = emptyList(),
    /** Every folder holding a short, whatever is currently picked. */
    val folders: List<ShortsFolder> = emptyList(),
    /** null means everywhere. */
    val folderId: Long? = null,
    val shuffled: Boolean = false,
    /** "312 of 1,284 files checked" while the walk is still running; null otherwise. */
    val measuringLine: String? = null,
    val measuringFraction: Float = 0f,
) {
    val isEmpty: Boolean get() = loaded && items.isEmpty()

    /** What the sheet's trigger says it is showing: a folder name, or everywhere. */
    val sourceLabel: String get() = folders.firstOrNull { it.id == folderId }?.label ?: EVERYWHERE

    companion object {
        const val EVERYWHERE = "Everywhere"
    }
}
