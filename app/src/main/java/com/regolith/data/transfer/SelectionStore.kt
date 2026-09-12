package com.regolith.data.transfer

import com.regolith.domain.transfer.FilePick
import com.regolith.domain.transfer.FolderPick
import com.regolith.domain.transfer.Selection
import com.regolith.domain.transfer.includeFile
import com.regolith.domain.transfer.includeFolder
import com.regolith.domain.transfer.toggleFile
import com.regolith.domain.transfer.toggleFolder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The live multi-selection, while the user is picking things to download.
 *
 * **Why it is in memory and not in Room.** `share_roots` is persisted
 * because it defines the library's shape and the scan reads it back. A
 * selection is a modal gesture inside one visit: an app that came back from
 * a process kill already in selection mode, with a count and a red
 * Download button and no memory of how it got there, would be a bug that
 * looked like a feature. Once the user taps Download the picks DO become
 * durable — as `transfers` rows and `download_picks` rows — because by then
 * the app has promised to fetch them.
 *
 * **Why it is a singleton and not ViewModel state.** `BrowseViewModel` is
 * created per `folderId` (assisted injection), so drilling from
 * `Series/Severance` into `Season 01` builds a NEW one. A selection held
 * there would evaporate on exactly the gesture this feature exists to
 * support. One app-scoped holder is also what lets Browse, Library and
 * Search contribute to a single batch without knowing about each other.
 *
 * Web analogy: a small store outside the component tree — the selection has
 * to outlive the route.
 */
@Singleton
class SelectionStore @Inject constructor() {

    private val _state = MutableStateFlow<Selection?>(null)

    /** Null means "not in selection mode"; a value means the chrome is up, even if nothing is picked. */
    val state: StateFlow<Selection?> = _state.asStateFlow()

    /** Enter selection mode with nothing picked. Idempotent: an active selection is left alone. */
    fun begin() {
        _state.update { it ?: Selection() }
    }

    /** Leave selection mode and forget the picks. */
    fun clear() {
        _state.value = null
    }

    /** What is picked right now, for the one-shot expansion at Download. */
    fun snapshot(): Selection? = _state.value

    /** Pick or unpick a folder, entering selection mode if a long press started here. */
    fun toggleFolder(pick: FolderPick) {
        _state.update { (it ?: Selection()).toggleFolder(pick) }
    }

    /** Pick or unpick a file, entering selection mode if a long press started here. */
    fun toggleFile(pick: FilePick) {
        _state.update { (it ?: Selection()).toggleFile(pick) }
    }

    /**
     * "Select all" on one screen: add everything visible, leaving picks made
     * elsewhere alone. Folders go in first so a file inside one of them is
     * dropped as covered rather than counted twice.
     */
    fun addAll(folders: List<FolderPick>, files: List<FilePick>) {
        _state.update { current ->
            // include*, not toggle*: on a screen full of rows that a picked
            // folder already covers, toggling would take them all OUT.
            var next = current ?: Selection()
            folders.forEach { next = next.includeFolder(it) }
            files.forEach { next = next.includeFile(it) }
            next
        }
    }
}
