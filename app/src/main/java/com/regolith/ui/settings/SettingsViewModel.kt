package com.regolith.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil3.ImageLoader
import com.regolith.data.artwork.ArtworkRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * State holder for the Settings tab. Survives rotation; cleared when the tab's
 * entry leaves the back stack. Web analogy: a store/hook that outlives
 * re-renders. Screens send events in, state flows out.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val artwork: ArtworkRepository,
    private val imageLoader: ImageLoader,
) : ViewModel() {
    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            artwork.observeCount().collect { count ->
                val bytes = withContext(Dispatchers.IO) { artwork.cacheSizeBytes() }
                _uiState.update { it.copy(artworkCount = count, artworkBytes = bytes) }
            }
        }
    }

    /** Settings › Media › Clear: forget every cached image and re-read on demand. */
    fun clearArtwork() {
        viewModelScope.launch {
            _uiState.update { it.copy(clearing = true) }
            withContext(Dispatchers.IO) { artwork.clearAll() }
            imageLoader.memoryCache?.clear()
            _uiState.update { it.copy(clearing = false, artworkBytes = 0) }
        }
    }
}
