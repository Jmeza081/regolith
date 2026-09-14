package com.regolith.desktop.ui.servers

import com.regolith.desktop.ui.Problem

/** Everything the Servers screen draws. */
data class ServersUiState(
    /** As typed: `smb://192.168.4.73/media`, `\\tower\media`, `tower:1445/media`. */
    val address: String = "",
    /** Blank signs in as guest. `DOMAIN\user` and `user@DOMAIN` work too. */
    val username: String = "",
    val password: String = "",
    val connecting: Boolean = false,
    val problem: Problem? = null,
) {
    val canConnect: Boolean get() = address.isNotBlank() && !connecting
}
