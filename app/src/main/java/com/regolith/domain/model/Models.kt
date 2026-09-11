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
    fun includes(relPath: String): Boolean = rootsCover(roots, relPath)
}

/**
 * Is [relPath] part of the library, given the folders chosen inside its
 * share? A path is in if it IS a chosen folder or sits under one; no chosen
 * folders at all means the whole share, which is what every share was
 * before folders could be chosen.
 *
 * The one place this rule is written. The scan asks it to decide whether a
 * folder may be read off the share at all, and a folder that is only on the
 * WAY to a chosen one is not: it gets a row so the tree keeps its shape,
 * and nothing more.
 */
fun rootsCover(roots: List<String>, relPath: String): Boolean =
    roots.isEmpty() || roots.any { relPath == it || relPath.startsWith("$it/") }

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
