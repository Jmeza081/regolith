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
    /**
     * The path or share is gone.
     *
     * [explanation] replaces the default wording when the app can say
     * something more useful than "not found" — see the share-root case in
     * `JcifsGateway`, where the server hands over the share and then refuses
     * to open it, which is a permission on the SERVER and reads as nonsense
     * otherwise.
     */
    class NotFound(
        path: String,
        cause: Throwable? = null,
        detail: String? = null,
        explanation: String? = null,
    ) : SmbFailure(explanation ?: "$path was not found", cause, detail)
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

/**
 * "Not found" on a SHARE'S OWN ROOT, worded as what it actually is.
 *
 * The server let us connect to the share and then said its root does not
 * exist, which cannot be true — we are holding a tree handle to it. In every
 * case seen so far the server could not READ the directory it is sharing and
 * reported that outward as a missing name. The owner lost an afternoon to
 * this one: macOS hands the share over happily while the kernel denies `smbd`
 * itself (`System Policy: smbd deny file-read-data /Volumes/...`), because
 * sharing an external volume needs a Full Disk Access grant that a fresh
 * install does not have. Every account failed identically, every dialect
 * failed identically, and the app said "not found" about a folder sitting
 * right there.
 *
 * Only the root qualifies: [path] is `share/` with nothing after it. A
 * missing path deeper in really is missing and should keep saying so.
 *
 * @param path the failing path as the gateway spells it, `"$share/$relPath"`.
 * @return the sentence to show instead, or null to keep the default wording.
 */
fun shareRootRefusal(path: String): String? {
    if (!path.endsWith("/")) return null
    val share = path.removeSuffix("/")
    if (share.isEmpty() || share.contains('/')) return null
    return "$share opened, but the server would not read it. That is a permission on the server, " +
        "not on this phone — on a Mac, give smbd Full Disk Access in Privacy & Security."
}
