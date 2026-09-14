package com.regolith.desktop.ui.editor

import com.regolith.data.media.SidecarWriter
import com.regolith.desktop.AppGraph
import com.regolith.desktop.navigation.Route
import com.regolith.desktop.player.FilmPlayer
import com.regolith.desktop.ui.childPath
import com.regolith.desktop.ui.toProblem
import com.regolith.domain.media.ChapterSidecar
import com.regolith.domain.playback.ChapterDraft
import com.regolith.domain.smb.SmbFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * State holder for one film's chapters.
 *
 * Every rule about what a set of chapters may look like is the phone's
 * [ChapterDraft]; every byte of the file is the phone's [ChapterSidecar] and
 * [SidecarWriter]. This class only wires them to a player and the share, so
 * a file saved here is the file the phone reads, by construction.
 *
 * There is no local copy on the Mac: the chapter file on the share is the
 * only record, so a save that fails leaves the edits unsaved and says so.
 */
class EditorViewModel(
    private val graph: AppGraph,
    private val route: Route.Editor,
    private val scope: CoroutineScope,
    private val player: FilmPlayer,
    /** Where blocking network calls run. Injected so tests control it; the app uses [Dispatchers.IO]. */
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val _state = MutableStateFlow(EditorUiState(title = route.video.name))
    val state: StateFlow<EditorUiState> = _state.asStateFlow()

    private val c get() = route.connection

    init {
        load()
    }

    private fun load() {
        scope.launch {
            try {
                // Both calls block on the network; keep them off the UI thread.
                val text = withContext(io) {
                    player.open(graph.gateway.open(c.host, c.credentials, c.share, childPath(route.folder, route.video.name)))
                    readSidecar()
                }
                val chapters = text?.let { ChapterSidecar.parse(it) }.orEmpty()
                _state.update {
                    it.copy(
                        loading = false,
                        hasSidecar = text != null,
                        draft = ChapterDraft.seed(fileId = 0, chapters = chapters, durationMs = player.durationMs.takeIf { d -> d > 0 } ?: UNKNOWN_DURATION),
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, problem = e.toProblem()) }
            }
        }
    }

    /** The film's chapter file, or null when it has none yet. Other failures propagate. */
    private suspend fun readSidecar(): String? = try {
        graph.sidecars.read(c.host, c.credentials, c.share, SidecarWriter.pathFor(route.folder, route.video.name, ChapterSidecar.SUFFIX))
    } catch (e: SmbFailure.NotFound) {
        null
    }

    /**
     * The player learns the runtime a moment after opening. Until then the
     * draft is seeded against an open-ended runtime; now marks past the end
     * are dropped and the end gap applies, keeping what was already edited.
     */
    fun onDurationKnown(durationMs: Long) = editDraft { d ->
        if (durationMs <= 0 || d.durationMs == durationMs) d
        else ChapterDraft.seed(d.fileId, d.marks, durationMs).copy(dirty = d.dirty).select(d.selected)
    }

    /** A new chapter where the film is now, or select the one already there. */
    fun mark() = editDraft { it.mark(player.positionMs) }

    /** Open a row (null closes it). Opening one takes the film to that chapter, as on the phone. */
    fun select(index: Int?) {
        editDraft { it.select(index) }
        index?.let { seekToMark(it) }
    }

    fun rename(index: Int, title: String) = editDraft { it.rename(index, title) }

    fun nudge(index: Int, deltaMs: Long) {
        editDraft { it.nudge(index, deltaMs) }
        seekToMark(index)
    }

    fun remove(index: Int) = editDraft { it.remove(index) }

    /** Write the chapter file to the share now. */
    fun save() {
        val s = _state.value
        val draft = s.draft ?: return
        if (s.saving) return
        _state.update { it.copy(saving = true, message = null) }
        scope.launch {
            try {
                graph.sidecars.write(c.host, c.credentials, c.share, route.folder, route.video.name, ChapterSidecar.format(draft.chapters))
                _state.update {
                    it.copy(
                        saving = false,
                        hasSidecar = true,
                        message = "Saved to the share",
                        // Edits made while the write was in flight stay unsaved.
                        draft = it.draft?.let { d -> if (d.marks == draft.marks) d.copy(dirty = false) else d },
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(saving = false, message = saveFailure(e)) }
            }
        }
    }

    private fun saveFailure(e: Exception): String = when (e) {
        is SmbFailure.Forbidden -> "Not saved: the share is read-only"
        is SmbFailure.Unreachable -> "Not saved: ${c.host.host} is out of reach"
        is SmbFailure.AuthFailed -> "Not saved: the share no longer accepts this sign-in"
        else -> "Not saved: ${e.toProblem().message}"
    }

    private fun seekToMark(index: Int) {
        _state.value.draft?.marks?.getOrNull(index)?.let { player.seekTo(it.startMs) }
    }

    private inline fun editDraft(change: (ChapterDraft) -> ChapterDraft) {
        _state.update { s -> s.draft?.let { s.copy(draft = change(it), message = null) } ?: s }
    }

    companion object {
        /** Stands in for the runtime until the player reports it; far past any film. */
        const val UNKNOWN_DURATION = Long.MAX_VALUE / 4
    }
}
