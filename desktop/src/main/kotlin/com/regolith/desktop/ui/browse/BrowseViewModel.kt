package com.regolith.desktop.ui.browse

import com.regolith.desktop.AppGraph
import com.regolith.desktop.navigation.Route
import com.regolith.desktop.ui.toProblem
import com.regolith.domain.media.ChapterSidecar
import com.regolith.domain.media.MediaFileTypes
import com.regolith.domain.smb.SmbEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * State holder for one folder of a share. Lists it on arrival and on
 * [refresh]; nothing is cached, because the share is the source of truth
 * and a Mac on the same network lists a folder in well under a second.
 */
class BrowseViewModel(
    private val graph: AppGraph,
    private val route: Route.Browse,
    private val scope: CoroutineScope,
) {
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
                _state.update { it.copy(loading = false, rows = rowsFor(entries)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, problem = e.toProblem()) }
            }
        }
    }

    companion object {
        /**
         * Folders first, then films, each by name; every other file is left
         * out (posters, notes, the chapter files themselves). A film "has
         * chapters" when its sidecar sits in the same listing, which costs
         * no extra request.
         */
        fun rowsFor(entries: List<SmbEntry>): List<BrowseRow> {
            val sidecars = entries.filterNot { it.isDirectory }.map { it.name }.filter { it.endsWith(ChapterSidecar.SUFFIX) }.toSet()
            return entries
                .filter { it.isDirectory || MediaFileTypes.isVideo(it.name) }
                .sortedWith(compareBy<SmbEntry>({ !it.isDirectory }, { it.name.lowercase() }))
                .map { e -> BrowseRow(e, hasChapters = !e.isDirectory && ChapterSidecar.sidecarNameFor(e.name) in sidecars) }
        }
    }
}
