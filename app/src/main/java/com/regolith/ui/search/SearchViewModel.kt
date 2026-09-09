package com.regolith.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.regolith.data.db.FolderEntity
import com.regolith.data.db.MediaFileEntity
import com.regolith.data.db.ScanRunEntity
import com.regolith.data.repository.LibraryRepository
import com.regolith.data.repository.SourceRepository
import com.regolith.data.scan.ScanRepository
import com.regolith.domain.library.FolderKind
import com.regolith.domain.library.ParsedName
import com.regolith.domain.playback.VideoInfo
import com.regolith.ui.util.formatBytes
import com.regolith.ui.util.formatDurationShort
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class SearchFilter(val label: String) { ALL("All"), UNWATCHED("Unwatched"), UHD("4K"), ON_DEVICE("On device") }

/** One row of results. Titles, raw filenames and folders share the list. */
sealed interface SearchHit {
    val testTag: String
    val primary: String
    val meta: String

    data class Title(val fileId: Long, override val primary: String, override val meta: String) : SearchHit {
        override val testTag get() = "search_title_$fileId"
    }

    data class File(val fileId: Long, override val primary: String, override val meta: String) : SearchHit {
        override val testTag get() = "search_file_$fileId"
    }

    data class Folder(val folderId: Long, val browsable: Boolean, override val primary: String, override val meta: String) : SearchHit {
        override val testTag get() = "search_folder_$folderId"
    }
}

data class SearchUiState(
    val query: String = "",
    val filter: SearchFilter = SearchFilter.ALL,
    val hits: List<SearchHit> = emptyList(),
    val recent: List<String> = emptyList(),
    /** "Still reading /media/archive — matches will keep arriving." */
    val scanningPath: String? = null,
    /** The user has typed something and results have been computed for it. */
    val searched: Boolean = false,
)

/**
 * Search (design section 06): filenames, parsed titles and folders in one
 * list, matched as you type. Everything comes from Room's full-text
 * indexes, so results keep arriving while a scan is still walking.
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val library: LibraryRepository,
    sources: SourceRepository,
    scans: ScanRepository,
) : ViewModel() {

    private val query = MutableStateFlow("")
    private val filter = MutableStateFlow(SearchFilter.ALL)

    private val results = query.debounce(150).flatMapLatest { q ->
        if (q.isBlank()) {
            flowOf(Triple(emptyList<MediaFileEntity>(), emptyList<FolderEntity>(), false))
        } else {
            combine(library.searchFiles(q, LIMIT), library.searchFolders(q, LIMIT)) { files, folders -> Triple(files, folders, true) }
        }
    }
    private val progress = results.flatMapLatest { (files, _, _) -> library.observeProgress(files.map { it.id }) }
    private val running = sources.observeEnabledShares().flatMapLatest { list ->
        if (list.isEmpty()) flowOf(emptyList()) else scans.observeLatest(list.map { it.id })
    }

    val uiState: StateFlow<SearchUiState> = combine(
        query, filter, results, progress, running, library.observeRecentSearches(8),
    ) { values ->
        val q = values[0] as String
        val f = values[1] as SearchFilter
        @Suppress("UNCHECKED_CAST")
        val result = values[2] as Triple<List<MediaFileEntity>, List<FolderEntity>, Boolean>
        val files = result.first
        val folders = result.second
        val searched = result.third
        @Suppress("UNCHECKED_CAST")
        val progressById = (values[3] as List<com.regolith.data.db.PlaybackProgressEntity>).associateBy { it.fileId }
        @Suppress("UNCHECKED_CAST")
        val runs = values[4] as List<ScanRunEntity>
        @Suppress("UNCHECKED_CAST")
        val recent = (values[5] as List<com.regolith.data.db.RecentSearchEntity>).map { it.query }

        val hits = mutableListOf<SearchHit>()
        for (file in files) {
            val p = progressById[file.id]
            val unwatched = p == null || (p.positionMs == 0L && !p.completed)
            when (f) {
                SearchFilter.UNWATCHED -> if (!unwatched) continue
                SearchFilter.UHD -> if (VideoInfo.resolutionLabelFor(file.width, file.height) != "4K") continue
                SearchFilter.ON_DEVICE -> continue // downloads arrive in Phase 5
                SearchFilter.ALL -> Unit
            }
            val parsed = ParsedName(file.titleParsed ?: file.name.substringBeforeLast('.'), file.year, file.season, file.episode)
            val res = VideoInfo.resolutionLabelFor(file.width, file.height).ifEmpty { null }
            val folderName = file.relPath.substringBeforeLast('/', "").substringAfterLast('/')
            if (parsed.matched) {
                hits += SearchHit.Title(file.id, parsed.display, listOfNotNull(res, file.durationMs?.let { formatDurationShort(it) }, folderName.ifEmpty { null }).joinToString(" · "))
            }
            hits += SearchHit.File(file.id, file.name, listOfNotNull(res, formatBytes(file.sizeBytes), "/" + file.relPath.substringBeforeLast('/', "")).joinToString(" · "))
        }
        if (f == SearchFilter.ALL) {
            for (folder in folders) {
                val kind = folder.kind?.let { runCatching { FolderKind.valueOf(it) }.getOrNull() }
                hits += SearchHit.Folder(
                    folderId = folder.id,
                    browsable = true,
                    primary = folder.name,
                    meta = listOfNotNull(if (kind == FolderKind.TITLE) "title" else "folder", "${folder.fileCount} files".takeIf { folder.fileCount > 0 }).joinToString(" · "),
                )
            }
        }
        SearchUiState(
            query = q,
            filter = f,
            hits = hits,
            recent = recent,
            scanningPath = runs.firstOrNull { it.status == ScanRunEntity.RUNNING }?.let { "/" + it.currentPath.ifEmpty { "…" } },
            searched = searched,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SearchUiState())

    fun setQuery(text: String) {
        query.value = text
    }

    fun setFilter(f: SearchFilter) {
        filter.value = f
    }

    /** Called when the user commits a query (opens a hit or hits enter). */
    fun remember(text: String = query.value) {
        if (text.isBlank()) return
        viewModelScope.launch { library.rememberSearch(text) }
    }

    fun clearRecent() {
        viewModelScope.launch { library.clearRecentSearches() }
    }

    private companion object {
        const val LIMIT = 40
    }
}
