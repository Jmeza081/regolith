package com.regolith.ui.addserver

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.regolith.data.repository.SourceRepository
import com.regolith.domain.model.Share
import com.regolith.domain.smb.SmbFailure
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** One folder at this level of the share, and how it stands with the library. */
data class FolderChoice(
    val name: String,
    /** Full path inside the share, the way the scan and Browse know it. */
    val relPath: String,
    /** Picked as a root itself. */
    val selected: Boolean,
    /** The root this folder already sits inside, when it does — picked by way of its parent. */
    val includedBy: String?,
    /**
     * Picks somewhere below this folder. It is what makes a deep choice
     * findable: an unpicked folder that says "2 chosen inside" is the
     * signpost back to them, and without it the only way to know would be to
     * open every folder on the share.
     */
    val chosenInside: Int = 0,
)

data class FolderPickerUiState(
    val shareName: String = "",
    /** The level being shown, `""` for the top of the share. */
    val relPath: String = "",
    val folders: List<FolderChoice> = emptyList(),
    /** True while the share is in the library as a whole, with no folders chosen. */
    val wholeShare: Boolean = false,
    /** How many folders are chosen across the share, for the Done button. */
    val chosenCount: Int = 0,
    val loading: Boolean = true,
    val error: String? = null,
) {
    /** "media / Films / 2016" */
    val breadcrumb: String get() = (listOf(shareName) + relPath.split('/').filter { it.isNotEmpty() }).joinToString(" / ")
    val title: String get() = relPath.substringAfterLast('/').ifEmpty { shareName }
}

/**
 * "Choose folders" (the drill-down under Choose a share). Lists one level
 * of the share straight off the server, marks what is already chosen, and
 * writes each pick as it is made — like the share picker, nothing here
 * waits for a Save.
 *
 * Two arguments come in through assisted injection: which share, and how
 * deep. Each level is its own ViewModel behind its own nav key, so back
 * simply pops one.
 */
@HiltViewModel(assistedFactory = FolderPickerViewModel.Factory::class)
class FolderPickerViewModel @AssistedInject constructor(
    @Assisted private val shareId: Long,
    @Assisted private val relPath: String,
    private val sources: SourceRepository,
) : ViewModel() {

    @AssistedFactory
    interface Factory {
        fun create(shareId: Long, relPath: String): FolderPickerViewModel
    }

    /** The listing: null until it lands, then names; the error, if the share would not answer. */
    private val listing = MutableStateFlow<Result<List<String>>?>(null)

    val uiState: StateFlow<FolderPickerUiState> = combine(sources.observeShare(shareId), listing) { share, listed ->
        val roots = share?.roots.orEmpty()
        val prefix = if (relPath.isEmpty()) "" else "$relPath/"
        FolderPickerUiState(
            shareName = share?.name ?: "",
            relPath = relPath,
            folders = listed?.getOrNull().orEmpty().map { name ->
                val path = prefix + name
                FolderChoice(
                    name = name,
                    relPath = path,
                    selected = path in roots,
                    includedBy = roots.firstOrNull { path.startsWith("$it/") },
                    chosenInside = roots.count { it.startsWith("$path/") },
                )
            },
            wholeShare = share?.enabled == true && roots.isEmpty(),
            chosenCount = roots.size,
            loading = listed == null,
            error = listed?.exceptionOrNull()?.message,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FolderPickerUiState(relPath = relPath))

    init {
        load()
    }

    /** List this level from the share. Also what "Try again" does. */
    fun load() {
        listing.value = null
        viewModelScope.launch {
            listing.value = try {
                Result.success(sources.listFolders(shareId, relPath))
            } catch (e: SmbFailure) {
                Result.failure(e)
            }
        }
    }

    /**
     * Pick or un-pick one folder. Picking narrows a whole-share choice down
     * to this folder; un-picking the last one widens back to the whole share
     * ([Share.roots] empty means everything), which the screen says out loud.
     *
     * Picking a folder that already has picks inside it REPLACES them: it is
     * the broader answer to the same question, and keeping both would scan
     * the inner folders twice.
     */
    fun toggle(folder: FolderChoice) {
        if (folder.includedBy != null) return // its parent already covers it
        viewModelScope.launch {
            if (folder.selected) sources.removeShareRoot(shareId, folder.relPath) else sources.addShareRoot(shareId, folder.relPath)
        }
    }
}
