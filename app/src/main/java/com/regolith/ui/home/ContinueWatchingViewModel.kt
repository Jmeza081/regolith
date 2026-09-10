package com.regolith.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.regolith.data.repository.LibraryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** What "All" beside Home's Continue watching row opens: everything started, nothing else. */
data class ContinueWatchingUiState(
    val items: List<ResumeItem> = emptyList(),
    val loaded: Boolean = false,
)

/**
 * The whole resume list, sharing [observeResume] with Home so the two can
 * never label the same title differently. [ALL_LIMIT] is a sanity bound,
 * not a page size: a library with more than 300 part-watched files is not
 * a list anyone scrolls.
 */
@HiltViewModel
class ContinueWatchingViewModel @Inject constructor(
    library: LibraryRepository,
) : ViewModel() {

    val uiState: StateFlow<ContinueWatchingUiState> = library.observeResume(ALL_LIMIT)
        .map { ContinueWatchingUiState(items = it, loaded = true) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ContinueWatchingUiState())

    private companion object {
        const val ALL_LIMIT = 300
    }
}
