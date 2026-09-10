package com.regolith.ui.settings

/** One server row under SHARES: "TOWER · SHOWING · 2.4 TB free", "STUDIO · out of reach". */
data class ServerRow(
    val serverId: Long,
    val name: String,
    /** "Scanning · 312 files", "2.4 TB free", "out of reach", "idle". */
    val status: String,
    val scanning: Boolean,
    /** White dot when reachable, grey when out of reach. */
    val reachable: Boolean = true,
    /** "SHOWING": its media is what Library lists. */
    val showing: Boolean = false,
) {
    val testTag get() = "settings_server_$serverId"
}

/**
 * Everything the Settings screen needs to draw, as one immutable value.
 * The ViewModel owns a single StateFlow<SettingsUiState>; the screen only reads.
 */
data class SettingsUiState(
    val title: String = "Settings",
    val servers: List<ServerRow> = emptyList(),
    /** Server the "Disconnect X?" confirm is open for. */
    val confirmDisconnect: ServerRow? = null,
    val hardwareDecoding: Boolean = true,
    val scrubThumbnails: Boolean = true,
    /** Images cached on the device, excluding placeholders. */
    val artworkCount: Int = 0,
    val artworkBytes: Long = 0,
    val clearing: Boolean = false,
    // --- Demo library (BuildConfig.DEMO_LIBRARY builds only).
    /** True while the demo server exists; the row offers the opposite action. */
    val demoInstalled: Boolean = false,
    /** True while it is being written or removed: both take a second or two. */
    val demoWorking: Boolean = false,
    /** What the demo's clips occupy on the device. */
    val demoBytes: Long = 0,
)
