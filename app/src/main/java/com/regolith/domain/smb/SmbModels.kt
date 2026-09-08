package com.regolith.domain.smb

/** A reachable SMB host: what the user typed, minus credentials. */
data class SmbHost(val host: String, val port: Int = DEFAULT_PORT) {
    companion object {
        const val DEFAULT_PORT = 445
    }
}

/** How to authenticate. Guest is what most home NAS boxes accept. */
sealed interface SmbCredentials {
    data object Guest : SmbCredentials
    data class Password(val username: String, val password: String, val domain: String? = null) : SmbCredentials
}

/** One share on a host, as the server reports it. */
data class SmbShareInfo(
    val name: String,
    /** Null when the server would not say (some do not answer size queries for guests). */
    val freeBytes: Long?,
    val totalBytes: Long?,
)

/** One directory entry. Paths inside a share are `/`-separated, no leading slash. */
data class SmbEntry(
    val name: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val modifiedAtMs: Long,
)

/**
 * Failures the UI distinguishes (design section 03 has a screen for each).
 * Anything else surfaces as [Other] with the library's message.
 */
sealed class SmbFailure(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** Wrong username/password, or the share refuses guests. */
    class AuthFailed(cause: Throwable? = null) : SmbFailure("Sign-in failed", cause)
    /** No route, connection refused, timed out, name did not resolve. */
    class Unreachable(host: String, cause: Throwable? = null) : SmbFailure("$host is out of reach", cause)
    /** The path or share is gone. */
    class NotFound(path: String, cause: Throwable? = null) : SmbFailure("$path was not found", cause)
    class Other(message: String, cause: Throwable? = null) : SmbFailure(message, cause)
}
