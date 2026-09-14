package com.regolith.desktop.data

import com.regolith.domain.smb.SmbCredentials
import com.regolith.domain.smb.SmbHost
import com.regolith.domain.smb.fromFields
import kotlinx.serialization.Serializable

/**
 * A share the Mac app remembers, as written to `servers.json`.
 *
 * There is deliberately no password here: it lives in the macOS Keychain
 * under [id] (see [KeychainCredentialStore]), the Mac's version of the
 * phone's guardrail G6. A copied `servers.json` holds nothing secret.
 */
@Serializable
data class SavedServer(
    /** Assigned by [ServerStore]; 0 means "not saved yet". Also the Keychain account name. */
    val id: Long = 0,
    val host: String,
    val port: Int = SmbHost.DEFAULT_PORT,
    val share: String,
    /** As typed: blank for guest, or `user`, `DOMAIN\user`, `user@DOMAIN`. */
    val username: String = "",
) {
    val isGuest: Boolean get() = username.isBlank()

    /** `smb://tower/media`, with the port when it is not 445: what the address field shows when editing. */
    val address: String
        get() = "smb://" + host + (if (port != SmbHost.DEFAULT_PORT) ":$port" else "") + "/" + share

    /** `tower/media`, as rows and titles show it. */
    val label: String
        get() = host + (if (port != SmbHost.DEFAULT_PORT) ":$port" else "") + "/" + share

    /** How to reach it with [password] (ignored for guest). */
    fun connection(password: String): Connection =
        Connection(SmbHost(host, port), SmbCredentials.fromFields(username, password), share)

    /** Same place, same sign-in: saving this again updates the row instead of adding a twin. */
    fun sameShareAs(other: SavedServer): Boolean =
        host.equals(other.host, ignoreCase = true) && port == other.port &&
            share.equals(other.share, ignoreCase = true) && username == other.username
}
