package com.regolith.ui.addserver

/** The manual-entry screen and its connecting / failed states, as one value. */
data class AddServerUiState(
    val address: String = "",
    val username: String = "",
    val password: String = "",
    val saveCredentials: Boolean = true,
    val phase: Phase = Phase.Editing,
    /** Set when the address field itself is wrong (nothing usable typed). */
    val addressError: String? = null,
    /** The message on the red card after a failed connect. */
    val error: String? = null,
    /** Small print under the message: NT status and dialect, for bug reports. */
    val errorDetail: String? = null,
    /** Non-null once connected; the screen navigates on and then clears it. */
    val connectedServerId: Long? = null,
) {
    enum class Phase { Editing, Connecting, Failed }

    val canConnect: Boolean get() = address.isNotBlank() && phase != Phase.Connecting
    val isGuest: Boolean get() = username.isBlank()
}
