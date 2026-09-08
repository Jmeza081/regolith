package com.regolith.ui.browse

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

/**
 * State holder for the Browse tab. Survives rotation; cleared when the tab's
 * entry leaves the back stack. Web analogy: a store/hook that outlives
 * re-renders. Screens send events in, state flows out.
 */
@HiltViewModel
class BrowseViewModel @Inject constructor() : ViewModel() {
    private val _uiState = MutableStateFlow(BrowseUiState())
    val uiState: StateFlow<BrowseUiState> = _uiState.asStateFlow()
}
