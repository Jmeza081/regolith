package com.regolith.ui.home

/** Home (design section 04). Phase 1: only the no-source and "has sources" shapes. */
data class HomeUiState(
    val loaded: Boolean = false,
    val serverNames: List<String> = emptyList(),
) {
    val hasSource: Boolean get() = serverNames.isNotEmpty()
}
