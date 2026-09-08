package com.regolith.ui.home

/**
 * Everything the Home screen needs to draw, as one immutable value.
 * The ViewModel owns a single StateFlow<HomeUiState>; the screen only reads.
 * Phase 0 placeholder: real fields arrive with the feature.
 */
data class HomeUiState(
    val title: String = "Home",
)
