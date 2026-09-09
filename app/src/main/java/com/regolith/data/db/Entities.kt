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
    /** Filled by the artwork pipeline or the container probe; null until then. */
    val durationMs: Long?,
    /** True when the last listing of its folder did not include it. */
    val missing: Boolean,
    val addedAtMs: Long,
    val lastSeenAtMs: Long,
    // --- Container probe (schema v2). Null until Title Detail or the
    // artwork pipeline has opened the file. Web analogy: lazily populated
    // columns, like a materialised `ffprobe`.
    val width: Int? = null,
    val height: Int? = null,
    val frameRate: Float? = null,
    /** Sample MIME type, e.g. "video/avc". */
    val videoCodec: String? = null,
    val hdr: Boolean? = null,
    val audioCodec: String? = null,
    val audioChannels: Int? = null,
    val audioSampleRate: Int? = null,
    /** When the full probe last ran; null means only what artwork extraction learned. */
    val probedAtMs: Long? = null,
)

/**
 * One cached image (guardrail G5). The bytes live in the app's own
 * directory at [relPath]; this row says where they came from and whether
 * the resolver gave up. Keyed by what it belongs to, so a rescan that keeps
 * the file id keeps its artwork.
 */
@Entity(
    tableName = "artwork",
    indices = [Index(value = ["ownerType", "ownerId", "kind"], unique = true)],
)
data class ArtworkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** "file" or "folder" ([com.regolith.domain.artwork.ArtworkOwner.typeName]). */
    val ownerType: String,
    val ownerId: Long,
    /** [com.regolith.domain.artwork.ArtworkKind] name. */
    val kind: String,
    /** [com.regolith.domain.artwork.ArtworkSource] name. PLACEHOLDER means nothing was readable. */
    val source: String,
    /** Path under the artwork directory, e.g. "file/12/thumb.jpg". Empty for a placeholder. */
    val relPath: String,
    val width: Int,
    val height: Int,
    val updatedAtMs: Long,
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
