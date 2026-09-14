package com.regolith.desktop.ui.editor

import com.regolith.desktop.ui.Problem
import com.regolith.domain.media.ChapterSidecar
import com.regolith.domain.playback.ChapterDraft

/** Where the chapters being edited came from, in the phone's order of preference. */
enum class ChapterOrigin {
    /** The film's `.chapters.txt` on the share. */
    FILE,
    /** Markers inside the film itself (MKV or MP4). */
    EMBEDDED,
    /** Neither: a single unnamed start mark. */
    NONE,
}

/** Everything the Editor screen draws, apart from the player's own clock. */
data class EditorUiState(
    /** The film's file name. */
    val title: String,
    val loading: Boolean = true,
    /** The chapters being edited; null until the film and its chapters are read. */
    val draft: ChapterDraft? = null,
    val origin: ChapterOrigin = ChapterOrigin.NONE,
    /** Whether the share has a chapter file for this film, as of opening or the last save or revert. */
    val hasSidecar: Boolean = false,
    val saving: Boolean = false,
    /** The Revert confirmation is showing. */
    val revertAsked: Boolean = false,
    val reverting: Boolean = false,
    /** Chapter names from the chapter files in this folder, for suggestions. Empty until read. */
    val folderNames: List<String> = emptyList(),
    /** The outcome of the last save or revert, in words. */
    val message: String? = null,
    /** Opening the film failed; the screen shows this instead of the editor. */
    val problem: Problem? = null,
) {
    /**
     * Save is on for any edit, and also for chapters read from inside the
     * film that have no chapter file yet: writing them out unchanged is a
     * reason to open the editor.
     */
    val canSave: Boolean
        get() = draft != null && !saving && (draft.dirty || (origin == ChapterOrigin.EMBEDDED && !hasSidecar))

    /** "Remove all chapters": inert while a row is open, and when there is nothing to remove. */
    val canClearAll: Boolean
        get() = draft != null && draft.selected == null && !(draft.marks.size == 1 && draft.marks[0].title == null)

    /** The line under "Chapters" when there is no fresher message. */
    val statusLine: String
        get() = when {
            hasSidecar -> "From the film's chapter file"
            origin == ChapterOrigin.EMBEDDED -> "From the chapters inside the film. Save writes them as a chapter file."
            else -> "No chapters yet"
        }

    /** The exact text Save would write, shown so the file is never a surprise. */
    val preview: String get() = draft?.let { ChapterSidecar.format(it.chapters) }.orEmpty()
}
