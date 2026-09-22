package com.regolith.ui.serverdetail

import com.regolith.domain.model.AddressMode
import com.regolith.domain.model.ServerAddress

/** One row in the "How to reach it" card. */
data class AddressRow(
    val address: ServerAddress,
    /** True for the address the server is actually using. */
    val inUse: Boolean,
    /** True when the owner has pinned this one, rather than it merely winning. */
    val pinned: Boolean,
) {
    val testTag get() = "server_address_${address.id}"
}

/**
 * Everything the server page needs to draw, as one immutable value.
 *
 * [mode] and [rows] are deliberately separate: which address is IN USE is a
 * measurement that can change under you, while which one is PINNED is a
 * choice you made. Showing them as one thing would make an automatic
 * choice look like a setting.
 */
data class ServerDetailUiState(
    val loaded: Boolean = false,
    val name: String = "",
    val mode: AddressMode = AddressMode.AUTO,
    val rows: List<AddressRow> = emptyList(),
    /** Shares on this server, for the folder and chapter rows. */
    val shareCount: Int = 0,
    val enabledShareCount: Int = 0,
    val fileCount: Int = 0,
    val lastScanAtMs: Long? = null,
    val scanning: Boolean = false,
    val scanFilesFound: Int = 0,
    val artworkRunning: Boolean = false,
    val artworkDone: Int = 0,
    val artworkTotal: Int = 0,
    val unreachable: Boolean = false,
    // --- open dialogs
    val renaming: Boolean = false,
    val addingAddress: Boolean = false,
    val labelling: ServerAddress? = null,
    val confirmRemove: ServerAddress? = null,
    val confirmDisconnect: Boolean = false,
    val confirmDisconnectKeeps: Int = 0,
    /** A one-off message for the page: "That address already belongs to STUDIO." */
    val message: String? = null,
) {
    /** True while the fastest-wins rule is in charge. */
    val automatic: Boolean get() = mode == AddressMode.AUTO

    /** "2 addresses", for the line under the name. */
    val addressSummary: String
        get() = when {
            rows.size == 1 -> rows.single().address.title
            else -> "${rows.size} addresses"
        }
}

/**
 * The sentence under the address card, which changes with the choice.
 *
 * It exists because pinning is a foot-gun worth naming: an address pinned
 * to the home network reads as "out of reach" everywhere else, and that is
 * exactly the failure this whole feature was built to stop someone walking
 * into by accident.
 */
fun addressHint(mode: AddressMode, inUse: ServerAddress?, count: Int): String = when {
    mode == AddressMode.AUTO && count <= 1 -> "One way in. Add another and Regolith will use whichever answers fastest."
    mode == AddressMode.AUTO -> "Nothing to remember when you leave the house — whichever answers fastest is the one used."
    inUse == null -> "Pinned to an address that is no longer here."
    else -> "Pinned to ${inUse.title}. Anywhere that address cannot be reached, this server will read as out of reach."
}
