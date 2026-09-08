package com.regolith.ui.browse

/**
 * Everything the Browse screen needs to draw, as one immutable value.
 * The ViewModel owns a single StateFlow<BrowseUiState>; the screen only reads.
 * Phase 0 placeholder: real fields arrive with the feature.
 */
data class BrowseUiState(
    val title: String = "Browse",
)
