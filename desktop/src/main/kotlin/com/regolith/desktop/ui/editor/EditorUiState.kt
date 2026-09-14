package com.regolith.desktop.ui.editor

import com.regolith.desktop.ui.Problem
import com.regolith.domain.media.ChapterSidecar
import com.regolith.domain.playback.ChapterDraft

/** Everything the Editor screen draws, apart from the player's own clock. */
data class EditorUiState(
    /** The film's file name. */
    val title: String,
    val loading: Boolean = true,
    /** The chapters being edited; null until the film and its chapter file are read. */
    val draft: ChapterDraft? = null,
    /** Whether the share had a chapter file when the film opened, or has one since a save. */
    val hasSidecar: Boolean = false,
    val saving: Boolean = false,
    /** The outcome of the last save, in words. */
    val message: String? = null,
    /** Opening the film failed; the screen shows this instead of the editor. */
    val problem: Problem? = null,
) {
    val canSave: Boolean get() = draft?.dirty == true && !saving

    /** The exact text Save would write, shown so the file is never a surprise. */
    val preview: String get() = draft?.let { ChapterSidecar.format(it.chapters) }.orEmpty()
}
