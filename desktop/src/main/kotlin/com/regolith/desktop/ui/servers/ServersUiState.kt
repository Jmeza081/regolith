package com.regolith.desktop.ui.servers

import com.regolith.desktop.data.SavedServer
import com.regolith.desktop.ui.Problem

/** Everything the Servers screen draws. */
data class ServersUiState(
    /** Remembered shares, by label. */
    val saved: List<SavedServer> = emptyList(),
    /** False until the saved list has been read once, so the screen does not flash "no shares". */
    val loaded: Boolean = false,
    /** The saved server the form is editing, or null when it adds a new one. */
    val editingId: Long? = null,
    /** As typed: `smb://192.168.4.73/media`, `\\tower\media`, `tower:1445/media`. */
    val address: String = "",
    /** Blank signs in as guest. `DOMAIN\user` and `user@DOMAIN` work too. */
    val username: String = "",
    val password: String = "",
    /** The form's Connect is working. */
    val connecting: Boolean = false,
    /** A saved row's connect is working. */
    val connectingId: Long? = null,
    /** The row waiting on the remove confirmation. */
    val removing: SavedServer? = null,
    val problem: Problem? = null,
) {
    val busy: Boolean get() = connecting || connectingId != null
    val canConnect: Boolean get() = address.isNotBlank() && !busy

    /** Editing a server with a stored password: a blank field keeps it. */
    val passwordPlaceholder: String?
        get() = if (editingId != null && username.isNotBlank()) "Leave blank to keep the saved password" else null
}
