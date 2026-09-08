package com.regolith.ui.library

/**
 * Everything the Library screen needs to draw, as one immutable value.
 * The ViewModel owns a single StateFlow<LibraryUiState>; the screen only reads.
 * Phase 0 placeholder: real fields arrive with the feature.
 */
data class LibraryUiState(
    val title: String = "Library",
)
