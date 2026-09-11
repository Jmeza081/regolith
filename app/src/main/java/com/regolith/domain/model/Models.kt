package com.regolith.domain.model

import com.regolith.domain.smb.SmbHost

/** How a stored server authenticates. The password itself is in the CredentialStore. */
enum class AuthMode { GUEST, PASSWORD }

/** A source server the user added (design: "TOWER · 192.168.1.24 · SMB3"). */
data class Server(
    val id: Long,
    val name: String,
    val host: SmbHost,
    val authMode: AuthMode,
    val username: String?,
    val lastSeenAtMs: Long?,
    /** Non-null while the server cannot be reached (design: "TOWER is out of reach · Last seen Tuesday"). */
    val unreachableSinceMs: Long? = null,
)

/** A share on a server. Only enabled shares are browsed and scanned. */
data class Share(
    val id: Long,
    val serverId: Long,
    val name: String,
    val enabled: Boolean,
    val freeBytes: Long?,
    /** When a full scan last finished; null means never. */
    val lastScanAtMs: Long? = null,
    /**
     * Folders inside the share chosen as the library's roots, as `/`-joined
     * paths. Empty means the whole share — the only choice there used to be.
     */
    val roots: List<String> = emptyList(),
) {
    /** True when [relPath] is one of the roots or sits inside one. Empty roots take everything. */
    fun includes(relPath: String): Boolean = roots.isEmpty() || roots.any { relPath == it || relPath.startsWith("$it/") }
}

/** A row on the Browse screen: a folder or a playable file. */
sealed interface BrowseItem {
    val name: String

    data class Folder(
        val id: Long,
        override val name: String,
        val fileCount: Int,
        val byteCount: Long,
    ) : BrowseItem

    data class File(
        val id: Long,
        override val name: String,
        val sizeBytes: Long,
        val modifiedAtMs: Long,
        /** Seconds already watched, or null when never started. */
        val progressMs: Long?,
        val durationMs: Long?,
        /** Picture size once something has opened the file; null until then. */
        val width: Int? = null,
        val height: Int? = null,
    ) : BrowseItem
}
