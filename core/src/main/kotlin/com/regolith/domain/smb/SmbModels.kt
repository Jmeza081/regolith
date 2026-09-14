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

    companion object
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
sealed class SmbFailure(
    message: String,
    cause: Throwable? = null,
    /** Technical detail for the error card's small print, e.g. "NT_STATUS_ACCESS_DENIED · SMB302". */
    val detail: String? = null,
) : Exception(message, cause) {
    /** Wrong username/password, or the share refuses guests. */
    class AuthFailed(cause: Throwable? = null, detail: String? = null) : SmbFailure("Sign-in failed", cause, detail)
    /** Signed in fine, but the server refused this particular request (e.g. listing shares). */
    class Forbidden(what: String, cause: Throwable? = null, detail: String? = null) : SmbFailure("Access denied: $what", cause, detail)
    /** No route, connection refused, timed out, name did not resolve. */
    class Unreachable(host: String, cause: Throwable? = null, detail: String? = null) : SmbFailure("$host is out of reach", cause, detail)
    /** The path or share is gone. */
    class NotFound(path: String, cause: Throwable? = null, detail: String? = null) : SmbFailure("$path was not found", cause, detail)
    class Other(message: String, cause: Throwable? = null, detail: String? = null) : SmbFailure(message, cause, detail)
}

/**
 * The username field accepts `user`, `DOMAIN\user` and `user@DOMAIN`.
 * Windows and some NAS boxes need the domain (or workgroup) to match.
 */
fun SmbCredentials.Companion.fromFields(username: String, password: String): SmbCredentials {
    val u = username.trim()
    if (u.isEmpty()) return SmbCredentials.Guest
    val back = u.indexOf('\\')
    if (back > 0) return SmbCredentials.Password(u.substring(back + 1), password, domain = u.substring(0, back))
    val at = u.indexOf('@')
    if (at > 0) return SmbCredentials.Password(u.substring(0, at), password, domain = u.substring(at + 1))
    return SmbCredentials.Password(u, password)
}
