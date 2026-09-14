package com.regolith.desktop.ui.browse

import com.regolith.desktop.AppGraph
import com.regolith.desktop.navigation.Route
import com.regolith.desktop.ui.childPath
import com.regolith.desktop.ui.toProblem
import com.regolith.domain.artwork.ArtworkCandidates
import com.regolith.domain.media.ChapterSidecar
import com.regolith.domain.media.MediaFileTypes
import com.regolith.domain.smb.SmbEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * State holder for one folder of a share. Lists it on arrival and on
 * [refresh]; nothing is cached, because the share is the source of truth
 * and a Mac on the same network lists a folder in well under a second.
 *
 * @param io where the extra listings that find folder posters run, off the
 *   window's thread; tests pass their own dispatcher so they can wait for them.
 */
class BrowseViewModel(
    private val graph: AppGraph,
    private val route: Route.Browse,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private var postersJob: Job? = null

    private val _state = MutableStateFlow(
        BrowseUiState(
            title = route.folder.substringAfterLast('/').ifEmpty { route.connection.share },
            location = listOf(route.connection.label, route.folder).filter { it.isNotEmpty() }.joinToString("/"),
        ),
    )
    val state: StateFlow<BrowseUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        _state.update { it.copy(loading = true, problem = null) }
        scope.launch {
            try {
                val c = route.connection
                val entries = graph.gateway.list(c.host, c.credentials, c.share, route.folder)
                val rows = rowsFor(entries, route.folder)
                _state.update { it.copy(loading = false, rows = rows) }
                findFolderPosters(rows)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, problem = e.toProblem()) }
            }
        }
    }

    /**
     * Looks inside each subfolder for its own poster (a poster, folder, cover
     * or thumb image, by the phone's rules) and puts it on the row when there
     * is one. It costs one listing per folder, so it runs a few at a time, off
     * the window's thread, after the rows are already on screen: a slow share
     * never holds up the list, and a folder that cannot be listed keeps its icon.
     */
    private fun findFolderPosters(rows: List<BrowseRow>) {
        postersJob?.cancel()
        val folders = rows.filter { it.isFolder }
        if (folders.isEmpty()) return
        val c = route.connection
        val slots = Semaphore(POSTER_LISTINGS_AT_ONCE)
        postersJob = scope.launch {
            folders.forEach { row ->
                launch {
                    val path = childPath(route.folder, row.entry.name)
                    val poster = slots.withPermit {
                        try {
                            withContext(io) {
                                val inside = graph.gateway.list(c.host, c.credentials, c.share, path)
                                ArtworkCandidates.forFolder(inside).firstOrNull()
                                    ?.let { cand -> inside.firstOrNull { it.name == cand.name } }
                                    ?.let { RowImage(childPath(path, it.name), it.sizeBytes) }
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            null
                        }
                    }
                    if (poster != null) {
                        _state.update { s -> s.copy(rows = s.rows.map { if (it.isFolder && it.entry.name == row.entry.name) it.copy(image = poster) else it }) }
                    }
                }
            }
        }
    }

    companion object {
        /** Folder listings for posters in flight at once: enough to fill a screen quickly, few enough to be polite to a NAS. */
        const val POSTER_LISTINGS_AT_ONCE = 4

        /**
         * Folders first, then films, each by name; every other file is left
         * out (posters, notes, the chapter files themselves). A film "has
         * chapters" when its sidecar sits in the same listing, which costs
         * no extra request, and its image is picked from that listing too.
         */
        fun rowsFor(entries: List<SmbEntry>, folder: String = ""): List<BrowseRow> {
            val sidecars = entries.filterNot { it.isDirectory }.map { it.name }.filter { it.endsWith(ChapterSidecar.SUFFIX) }.toSet()
            return entries
                .filter { it.isDirectory || MediaFileTypes.isVideo(it.name) }
                .sortedWith(compareBy<SmbEntry>({ !it.isDirectory }, { it.name.lowercase() }))
                .map { e ->
                    BrowseRow(
                        e,
                        hasChapters = !e.isDirectory && ChapterSidecar.sidecarNameFor(e.name) in sidecars,
                        image = if (e.isDirectory) null else imageFor(e, entries)?.let { RowImage(childPath(folder, it.name), it.sizeBytes) },
                    )
                }
        }

        /**
         * The image a film's row shows: the first of the phone's candidates
         * ([ArtworkCandidates.forFile]), which is an image with the film's
         * name, or a poster beside a film that is alone in its folder.
         */
        private fun imageFor(film: SmbEntry, entries: List<SmbEntry>): SmbEntry? =
            ArtworkCandidates.forFile(film.name, entries).firstOrNull()?.let { c -> entries.firstOrNull { it.name == c.name } }
    }
}
