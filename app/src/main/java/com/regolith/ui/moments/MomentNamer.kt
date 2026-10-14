package com.regolith.ui.moments

import com.regolith.data.repository.UserChapterRepository
import com.regolith.domain.playback.Chapter
import com.regolith.domain.playback.ChapterDraft
import com.regolith.domain.playback.ChapterWriteOutcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** A video's chapters as they are on screen: what a quick name is written into. */
data class ShownChapters(val fileId: Long, val chapters: List<Chapter>, val durationMs: Long)

/**
 * What the quick name sheet is naming: one chapter at its start (the video
 * page, the Chapters panel), or a new mark at the play time ([isNew], the
 * player's Mark pill).
 */
data class MomentNameTarget(
    val fileId: Long,
    val atMs: Long,
    val isNew: Boolean,
    /** "Part 3 of 9" over the sheet's title; null for a new mark, which says its time instead. */
    val part: String? = null,
    /** What the field shows while empty, and what an unnamed save is called on screen: "Part 3". */
    val placeholder: String,
    /** Where the chapter ends, for its length under the title; null for a new mark. */
    val endMs: Long? = null,
    /** The picture's time ([com.regolith.domain.playback.ChapterStills]); null draws none (the paused film is the picture). */
    val frameMs: Long? = null,
)

/** The sheet while it is open: what it names, what is typed, and whether Save is writing. */
data class MomentNaming(val target: MomentNameTarget, val text: String = "", val saving: Boolean = false)

/** One finished quick name, for the "saved" message and its Undo. */
data class MomentSaved(val title: String?, val atMs: Long, val outcome: ChapterWriteOutcome, val undo: () -> Unit)

/**
 * The quick name sheet's state and its one save, shared by the video page
 * and the player. Web analogy: a custom hook both screens call, owned by
 * each screen's ViewModel and living in its scope.
 *
 * A tap on a chip saves that name and closes the sheet; typing narrows the
 * same chips the chapter editor offers ([UserChapterRepository.suggestionsFor]).
 * The save goes through [UserChapterRepository.nameAt], so naming one part
 * of the even split keeps the whole split as the user's chapters.
 *
 * @param current the chapters on screen now, read at save time rather than
 *   when the sheet opened, so a sync landing meanwhile is not written over.
 * @param onClosed called whenever the sheet goes, saved or not: the player
 *   resumes a film it paused for a new mark.
 */
class MomentNamer(
    private val scope: CoroutineScope,
    private val chapters: UserChapterRepository,
    private val current: () -> ShownChapters?,
    private val onClosed: () -> Unit = {},
) {
    val naming: StateFlow<MomentNaming?> get() = _naming
    private val _naming = MutableStateFlow<MomentNaming?>(null)

    /** The chips under the field, following what is typed. */
    val suggestions: StateFlow<List<String>> = chapters.suggestionsFor(_naming.map { it?.text })
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** One per save, for the snackbar with its Undo. */
    val saved: SharedFlow<MomentSaved> get() = _saved
    private val _saved = MutableSharedFlow<MomentSaved>(extraBufferCapacity = 1)

    fun open(target: MomentNameTarget) {
        _naming.value = MomentNaming(target)
    }

    fun type(text: String) {
        _naming.value = _naming.value?.takeUnless { it.saving }?.copy(text = text.take(ChapterDraft.NAME_MAX))
    }

    /**
     * Move a new mark by [deltaMs], kept inside the film the way the editor
     * keeps marks ([ChapterDraft.MIN_GAP_MS] clear of the end). Returns where
     * it landed, for the player to seek to; null when there is nothing to move.
     */
    fun nudge(deltaMs: Long): Long? {
        val open = _naming.value?.takeIf { it.target.isNew && !it.saving } ?: return null
        val durationMs = current()?.durationMs ?: return null
        val at = (open.target.atMs + deltaMs).coerceIn(0L, (durationMs - ChapterDraft.MIN_GAP_MS).coerceAtLeast(0L))
        _naming.value = open.copy(target = open.target.copy(atMs = at, placeholder = newMarkPlaceholder(at)))
        return at
    }

    fun cancel() {
        if (_naming.value?.saving == true) return
        close()
    }

    /** Save [name], or what was typed: a chip's tap passes its name. Blank saves the mark unnamed. */
    fun save(name: String? = null) {
        val open = _naming.value ?: return
        if (open.saving) return
        val shown = current()?.takeIf { it.fileId == open.target.fileId && it.durationMs > 0 } ?: return close()
        val title = (name ?: open.text).trim()
        _naming.value = open.copy(text = name ?: open.text, saving = true)
        scope.launch {
            val fileId = open.target.fileId
            val before = chapters.observe(fileId).first()
            val outcome = chapters.nameAt(fileId, shown.chapters, shown.durationMs, open.target.atMs, title)
            close()
            _saved.tryEmit(
                MomentSaved(title.ifEmpty { null }, open.target.atMs, outcome) {
                    scope.launch { chapters.restore(fileId, before, shown.durationMs) }
                },
            )
        }
    }

    private fun close() {
        if (_naming.value == null) return
        _naming.value = null
        onClosed()
    }

    companion object {
        fun newMarkPlaceholder(atMs: Long) = "Moment at ${com.regolith.ui.util.formatClock(atMs)}"
    }
}
