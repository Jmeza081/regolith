package com.regolith.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.regolith.data.repository.SourceRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** Home state: which servers exist. Continue-watching and newly-added rows arrive in Phase 4. */
@HiltViewModel
class HomeViewModel @Inject constructor(
    sources: SourceRepository,
) : ViewModel() {
    val uiState: StateFlow<HomeUiState> = sources.observeServers()
        .map { servers -> HomeUiState(loaded = true, serverNames = servers.map { it.name }) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())
}
