package com.regolith.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/*
 * Room entities = table definitions. Room is SQLite with a typed API;
 * think of an entity as a Prisma/Drizzle model.
 *
 * Identity rules (guardrail G3):
 *  - Every remote thing is keyed by where it lives: (serverId), (serverId,
 *    share name), (shareId, relPath). Ids are stable across rescans.
 *  - A rescan never deletes a file row; it sets `missing = true`. Progress,
 *    artwork and transfers keep their foreign keys that way.
 */

@Entity(tableName = "servers", indices = [Index(value = ["host", "port"], unique = true)])
data class ServerEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Display name, usually the NetBIOS/DNS name uppercased: "TOWER". */
    val name: String,
    val host: String,
    val port: Int,
    /** "GUEST" or "PASSWORD". The password itself lives in the CredentialStore. */
    val authMode: String,
    val username: String?,
    val lastSeenAtMs: Long?,
    val createdAtMs: Long,
)

@Entity(
    tableName = "shares",
    foreignKeys = [ForeignKey(ServerEntity::class, ["id"], ["serverId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["serverId", "name"], unique = true)],
)
data class ShareEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val serverId: Long,
    val name: String,
    /** Only enabled shares are browsed and scanned. */
    val enabled: Boolean,
    val freeBytes: Long?,
    val totalBytes: Long?,
    val lastScanAtMs: Long?,
)

@Entity(
    tableName = "folders",
    foreignKeys = [ForeignKey(ShareEntity::class, ["id"], ["shareId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["shareId", "relPath"], unique = true), Index("parentId")],
)
data class FolderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val shareId: Long,
    /** Null for the share root. */
    val parentId: Long?,
    /** `/`-separated, no leading slash; `""` for the share root. */
    val relPath: String,
    val name: String,
    /** Direct playable files, as of the last listing. */
    val fileCount: Int,
    val byteCount: Long,
    val lastListedAtMs: Long?,
)

@Entity(
    tableName = "media_files",
    foreignKeys = [
        ForeignKey(ShareEntity::class, ["id"], ["shareId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(FolderEntity::class, ["id"], ["folderId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index(value = ["shareId", "relPath"], unique = true), Index("folderId")],
)
data class MediaFileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val shareId: Long,
    val folderId: Long,
    val relPath: String,
    val name: String,
    val ext: String,
    val sizeBytes: Long,
    val modifiedAtMs: Long,
    /** Filled by Phase 3/4 probing; null until then. */
    val durationMs: Long?,
    /** True when the last listing of its folder did not include it. */
    val missing: Boolean,
    val addedAtMs: Long,
    val lastSeenAtMs: Long,
)

@Entity(
    tableName = "playback_progress",
    foreignKeys = [ForeignKey(MediaFileEntity::class, ["id"], ["fileId"], onDelete = ForeignKey.CASCADE)],
)
data class PlaybackProgressEntity(
    @PrimaryKey val fileId: Long,
    val positionMs: Long,
    val durationMs: Long,
    val completed: Boolean,
    val updatedAtMs: Long,
)
