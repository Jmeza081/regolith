package com.regolith.domain.smb

/** What the user typed into "Enter an address", understood. */
data class ParsedSmbAddress(
    val host: SmbHost,
    /** Present when the address named a share, e.g. `smb://tower/media`. */
    val share: String? = null,
)

/**
 * Parses the address field of the manual-entry screen. Accepts what people
 * actually type: `smb://192.168.1.24`, `smb://tower/media/`, `\\tower\media`,
 * a bare hostname or IP, and an optional `:port`.
 *
 * Pure Kotlin so the rules are unit-tested without an emulator.
 */
object SmbAddressParser {

    /** Returns null when nothing usable was typed. */
    fun parse(raw: String): ParsedSmbAddress? {
        var s = raw.trim()
        if (s.isEmpty()) return null
        s = s.removePrefix("smb://").removePrefix("SMB://").removePrefix("\\\\").replace('\\', '/')
        val parts = s.split('/').filter { it.isNotBlank() }
        if (parts.isEmpty()) return null

        val hostPart = parts[0]
        val (hostName, port) = splitPort(hostPart) ?: return null
        if (hostName.isBlank()) return null

        val share = parts.getOrNull(1)
        return ParsedSmbAddress(SmbHost(hostName.lowercase(), port), share)
    }

    private fun splitPort(hostPart: String): Pair<String, Int>? {
        val idx = hostPart.lastIndexOf(':')
        if (idx < 0) return hostPart to SmbHost.DEFAULT_PORT
        val port = hostPart.substring(idx + 1).toIntOrNull() ?: return null
        if (port !in 1..65535) return null
        return hostPart.substring(0, idx) to port
    }
}
