package com.regolith.desktop.ui.editor

import com.regolith.data.media.SidecarWriter
import com.regolith.desktop.AppGraph
import com.regolith.desktop.navigation.Route
import com.regolith.desktop.player.FilmPlayer
import com.regolith.desktop.ui.childPath
import com.regolith.desktop.ui.formatClock
import com.regolith.desktop.ui.toProblem
import com.regolith.domain.media.ChapterParser
import com.regolith.domain.media.ChapterSidecar
import com.regolith.domain.playback.Chapter
import com.regolith.domain.playback.ChapterDraft
import com.regolith.domain.smb.SeekableByteSource
import com.regolith.domain.smb.SmbEntry
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
import org.slf4j.LoggerFactory

/**
 * State holder for one film's chapters.
 *
 * Every rule about what a set of chapters may look like is the phone's
 * [ChapterDraft]; every byte of the file is the phone's [ChapterSidecar] and
 * [SidecarWriter]; chapters inside a film are read by the phone's
 * [ChapterParser]. This class only wires them to a player and the share, so
 * a file saved here is the file the phone reads, by construction.
 *
 * The chapters start from, in the phone's order: the film's chapter file,
 * else the markers inside the film, else one unnamed start mark.
 *
 * **Nothing is opened that a folder listing has not shown.** jcifs opens a
 * file for reading with create-if-missing (open flags 17, `O_CREAT |
 * O_RDONLY`, in jcifs-ng 2.1.10), so looking for a chapter file by opening
 * it would leave an empty one on the share, which would then count as the
 * film's chapter file and hide the chapters inside it. There is
 * no local copy on the Mac: the chapter file on the share is the only
 * record, so a save that fails leaves the edits unsaved and says so.
 */
class EditorViewModel(
    private val graph: AppGraph,
    private val route: Route.Editor,
    private val scope: CoroutineScope,
    private val player: FilmPlayer,
    /** Where blocking network calls run. Injected so tests control it; the app uses [Dispatchers.IO]. */
    private val io: CoroutineDispatcher = Dispatchers.IO,
    /** Reads the markers inside a film. Injected so tests need no real MKV. */
    private val readEmbedded: (SeekableByteSource) -> List<Chapter> = ChapterParser::read,
) {
    private val _state = MutableStateFlow(EditorUiState(title = route.video.name))
    val state: StateFlow<EditorUiState> = _state.asStateFlow()

    private val c get() = route.connection
    private val filmPath get() = childPath(route.folder, route.video.name)

    init {
        load()
    }

    private fun load() {
        scope.launch {
            try {
                // All of these block on the network; keep them off the UI thread.
                val (listing, text, embedded) = withContext(io) {
                    val listing = graph.gateway.list(c.host, c.credentials, c.share, route.folder)
                    // Gone since Browse listed it: say so, rather than let the
                    // open below create an empty file under the film's name.
                    if (listing.none { !it.isDirectory && it.name == route.video.name }) throw SmbFailure.NotFound("${c.share}/$filmPath")
                    player.open(graph.gateway.open(c.host, c.credentials, c.share, filmPath))
                    val sidecarName = ChapterSidecar.sidecarNameFor(route.video.name)
                    // An empty chapter file holds no chapters: treat it as no file, as the
                    // phone does for downloads. Such files are what a create-on-open left
                    // behind, and counting them would hide the chapters inside the film.
                    val text = if (listing.any { !it.isDirectory && it.name == sidecarName }) readSidecar()?.takeIf { it.isNotBlank() } else null
                    // Only needed when there is no file; a file always wins.
                    Triple(listing, text, if (text == null) readEmbeddedChapters() else emptyList())
                }
                val fromFile = text?.let { ChapterSidecar.parse(it) }.orEmpty()
                val origin = when {
                    text != null -> ChapterOrigin.FILE
                    embedded.isNotEmpty() -> ChapterOrigin.EMBEDDED
                    else -> ChapterOrigin.NONE
                }
                _state.update {
                    it.copy(
                        loading = false,
                        hasSidecar = text != null,
                        origin = origin,
                        draft = ChapterDraft.seed(fileId = 0, chapters = if (text != null) fromFile else embedded, durationMs = knownDuration()),
                    )
                }
                // Suggestions come after the editor is usable; this swallows its own failures.
                loadFolderNames(listing)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, problem = e.toProblem()) }
            }
        }
    }

    /** The film's chapter file, read only after a listing showed it; null if it vanished since. Other failures propagate. */
    private suspend fun readSidecar(): String? = try {
        graph.sidecars.read(c.host, c.credentials, c.share, SidecarWriter.pathFor(route.folder, route.video.name, ChapterSidecar.SUFFIX))
    } catch (e: SmbFailure.NotFound) {
        null
    }

    /** The markers inside the film, or none. A film that cannot be read for chapters still opens. */
    private fun readEmbeddedChapters(): List<Chapter> = try {
        graph.gateway.open(c.host, c.credentials, c.share, filmPath).use(readEmbedded)
    } catch (e: SmbFailure) {
        log.warn("Could not read the chapters inside {}: {}", filmPath, e.message)
        emptyList()
    }

    /**
     * Names from every chapter file in this folder, for suggestions, taken
     * from the listing the film was opened with. Best effort and in the
     * background: a file that cannot be read simply offers nothing.
     */
    private suspend fun loadFolderNames(listing: List<SmbEntry>) {
        val names = try {
            withContext(io) {
                listing
                    .filter { !it.isDirectory && it.name.endsWith(ChapterSidecar.SUFFIX) && it.sizeBytes <= ChapterSidecar.MAX_BYTES }
                    .take(MAX_FOLDER_SIDECARS)
                    .flatMap { entry ->
                        val text = try {
                            graph.sidecars.read(c.host, c.credentials, c.share, childPath(route.folder, entry.name))
                        } catch (e: SmbFailure) {
                            null
                        }
                        text?.let { ChapterSidecar.parse(it) }.orEmpty().mapNotNull { it.title }
                    }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Could not read the folder's chapter names: {}", e.message)
            emptyList()
        }
        _state.update { it.copy(folderNames = names) }
    }

    private fun knownDuration(): Long = player.durationMs.takeIf { it > 0 } ?: UNKNOWN_DURATION

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

    /**
     * A new chapter a minute after the last one, open for its Start to be
     * typed: how chapters are added when there is no video to mark from.
     */
    fun addChapter() = editDraft { d -> d.mark(d.marks.last().startMs + ADD_STEP_MS) }

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

    /** Back to a single unnamed start mark. Nothing is written until Save. */
    fun clearAll() = editDraft { it.clearAll() }

    /**
     * Move mark [index] to a typed time (`12:30`, `0:12:30`, `1:02:15.5`, or
     * bare seconds) and take the film there. Returns the problem in the
     * phone's words, or null when the mark moved.
     */
    fun typeStart(index: Int, text: String): String? {
        val draft = _state.value.draft ?: return null
        val ms = ChapterDraft.parseClock(text) ?: return "Use 12:30, 0:12:30 or 1:02:15.5"
        val range = draft.bounds(index) ?: return "This one cannot move"
        if (ms !in range) {
            return if (range.last >= UNKNOWN_DURATION / 2) "After ${formatClock(range.first)} here"
            else "Between ${formatClock(range.first)} and ${formatClock(range.last)} here"
        }
        editDraft { it.move(index, ms) }
        seekToMark(index)
        return null
    }

    fun askRevert() = _state.update { if (it.hasSidecar) it.copy(revertAsked = true) else it }
    fun cancelRevert() = _state.update { it.copy(revertAsked = false) }

    /**
     * Delete the film's chapter file from the share. Like the phone, the
     * film then falls back to its own markers if it has any, else a single
     * unnamed start mark.
     */
    fun confirmRevert() {
        if (_state.value.reverting) return
        _state.update { it.copy(revertAsked = false, reverting = true, message = null) }
        scope.launch {
            try {
                val embedded = withContext(io) {
                    graph.sidecars.delete(c.host, c.credentials, c.share, route.folder, route.video.name)
                    readEmbeddedChapters()
                }
                _state.update {
                    val d = it.draft
                    it.copy(
                        reverting = false,
                        hasSidecar = false,
                        origin = if (embedded.isEmpty()) ChapterOrigin.NONE else ChapterOrigin.EMBEDDED,
                        message = "Reverted: the chapter file is gone",
                        draft = d?.let { ChapterDraft.seed(d.fileId, embedded, d.durationMs) },
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(reverting = false, message = failure("Not reverted", e)) }
            }
        }
    }

    /** Write the chapter file to the share now. [onSaved] runs only when the write succeeded. */
    fun save(onSaved: () -> Unit = {}) {
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
                        origin = ChapterOrigin.FILE,
                        message = "Saved to the share",
                        // Edits made while the write was in flight stay unsaved.
                        draft = it.draft?.let { d -> if (d.marks == draft.marks) d.copy(dirty = false) else d },
                    )
                }
                onSaved()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(saving = false, message = failure("Not saved", e)) }
            }
        }
    }

    private fun failure(what: String, e: Exception): String = when (e) {
        is SmbFailure.Forbidden -> "$what: the share is read-only"
        is SmbFailure.Unreachable -> "$what: ${c.host.host} is out of reach"
        is SmbFailure.AuthFailed -> "$what: the share no longer accepts this sign-in"
        else -> "$what: ${e.toProblem().message}"
    }

    private fun seekToMark(index: Int) {
        _state.value.draft?.marks?.getOrNull(index)?.let { player.seekTo(it.startMs) }
    }

    private inline fun editDraft(change: (ChapterDraft) -> ChapterDraft) {
        _state.update { s -> s.draft?.let { s.copy(draft = change(it), message = null) } ?: s }
    }

    companion object {
        private val log = LoggerFactory.getLogger("Regolith/Editor")

        /** Stands in for the runtime until the player reports it; far past any film. */
        const val UNKNOWN_DURATION = Long.MAX_VALUE / 4

        /** Where [addChapter] puts a new mark, after the last. */
        const val ADD_STEP_MS = 60_000L

        /** More chapter files than this in one folder are not read for suggestions. */
        const val MAX_FOLDER_SIDECARS = 200
    }
}
