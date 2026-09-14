package com.regolith.desktop.data

import com.regolith.domain.smb.SmbCredentials
import com.regolith.domain.smb.SmbHost

/**
 * One open share: which host, as whom, which share. Everything past the
 * Servers screen needs exactly this to reach the NAS.
 *
 * Never log or print one: [credentials] may hold a password.
 */
data class Connection(
    val host: SmbHost,
    val credentials: SmbCredentials,
    val share: String,
) {
    /** "192.168.4.73/media", or "localhost:1445/media" on a non-default port. */
    val label: String
        get() = buildString {
            append(host.host)
            if (host.port != SmbHost.DEFAULT_PORT) append(':').append(host.port)
            append('/').append(share)
        }
}
