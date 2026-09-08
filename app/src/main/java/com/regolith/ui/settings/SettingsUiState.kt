package com.regolith.ui.settings

/**
 * Everything the Settings screen needs to draw, as one immutable value.
 * The ViewModel owns a single StateFlow<SettingsUiState>; the screen only reads.
 * Phase 0 placeholder: real fields arrive with the feature.
 */
data class SettingsUiState(
    val title: String = "Settings",
)
