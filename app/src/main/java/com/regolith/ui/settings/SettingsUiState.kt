package com.regolith.ui.settings

/**
 * Everything the Settings screen needs to draw, as one immutable value.
 * The ViewModel owns a single StateFlow<SettingsUiState>; the screen only reads.
 * Phase 3 adds the media cache; shares and playback preferences arrive in Phase 6.
 */
data class SettingsUiState(
    val title: String = "Settings",
    /** Images cached on the device, excluding placeholders. */
    val artworkCount: Int = 0,
    val artworkBytes: Long = 0,
    val clearing: Boolean = false,
)
