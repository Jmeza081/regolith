package com.regolith.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Fts4
import androidx.room.FtsOptions
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
    /** Schema v4: set when a listing, scan or transfer could not reach the server; cleared on the next success. */
    val unreachableSinceMs: Long? = null,
    /**
     * Schema v12: how [host] and [port] above are chosen — "AUTO" or "PINNED".
     *
     * AUTO means the columns are a CACHE of whichever address answered last,
     * kept up to date by the resolver; PINNED means the user chose one and
     * nothing may change it. Everything in the app that opens an SMB
     * connection reads `host`/`port`, which is exactly why they stay: the
     * address set is new, the way the rest of the app asks for an address is
     * not ([ServerAddressEntity]).
     */
    @ColumnInfo(defaultValue = "AUTO") val addressMode: String = "AUTO",
)

/**
 * Schema v12: one way to reach a server.
 *
 * **Why a server stopped being its address.** Identity used to BE the
 * address — `servers` is uniquely indexed on (host, port) — so the same Mac
 * reached at `192.168.4.82` at home and over a VPN from a hotel was two
 * servers, with two scans, two artwork caches and, worst of all, two sets of
 * resume points. The only way to have both was to keep two copies of one
 * library and watch them drift apart.
 *
 * A server is a MACHINE; an address is a route to it. Splitting them means
 * the library, the artwork and the progress all hang off one `serverId`
 * however you got there, which is the whole point of the change.
 *
 * Every address of a server shares its credentials: it is one machine, and
 * one login.
 *
 * Web analogy: a host with several A records, chosen per request.
 */
@Entity(
    tableName = "server_addresses",
    foreignKeys = [ForeignKey(ServerEntity::class, ["id"], ["serverId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["serverId", "host", "port"], unique = true), Index("serverId")],
)
data class ServerAddressEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val serverId: Long,
    /**
     * What the owner calls this route: "Home", "Tailscale". Blank means
     * "no name yet", and the UI shows the address itself — the same bargain
     * [com.regolith.domain.model.serverNameOrDefault] makes for a server's
     * name, and the reason the backfill below can leave it empty.
     */
    val label: String,
    val host: String,
    val port: Int,
    val createdAtMs: Long,
    /** When this address last answered, for "answered in 2 ms" and for ordering by what works. */
    val lastOkAtMs: Long? = null,
    /** How long it took to answer, in milliseconds. Null until it has been tried. */
    val lastRttMs: Int? = null,
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
    /** Schema v9: whether Done writes `<basename>.chapters.txt` to this share. Off keeps chapters on the phone. */
    @ColumnInfo(defaultValue = "1") val writeChapters: Boolean = true,
)

/**
 * Schema v5: a folder the user picked as a library root inside a share
 * (the "Choose folders" step of Add Server). No rows for a share means the
 * whole share, which is what every share was before this table existed.
 * The scan walks these instead of the share root, and Browse shows them
 * as the share's top level; everything else on the share is invisible to
 * the app. Web analogy: an allowlist of paths on a mount.
 */
@Entity(
    tableName = "share_roots",
    foreignKeys = [ForeignKey(ShareEntity::class, ["id"], ["shareId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["shareId", "relPath"], unique = true)],
)
data class ShareRootEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val shareId: Long,
    /** `/`-separated, no leading slash, never empty (that would be the share itself). */
    val relPath: String,
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
    // --- Schema v3: what the scan understood (design section 08).
    /** [com.regolith.domain.library.FolderKind] name; null until listed by a v3 build. */
    val kind: String? = null,
    /** "Arrival" for `Arrival (2016)`; the name itself when nothing parsed. */
    val titleParsed: String? = null,
    val year: Int? = null,
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
    /**
     * Clockwise rotation the container asks for (schema v10): 0/90/180/270,
     * null until measured. Width and height above are the STORED size, so a
     * portrait phone clip is commonly 1920x1080 with 90 here.
     */
    val rotationDegrees: Int? = null,
    val frameRate: Float? = null,
    /** Sample MIME type, e.g. "video/avc". */
    val videoCodec: String? = null,
    val hdr: Boolean? = null,
    val audioCodec: String? = null,
    val audioChannels: Int? = null,
    val audioSampleRate: Int? = null,
    /** When the full probe last ran; null means only what artwork extraction learned. */
    val probedAtMs: Long? = null,
    // --- Schema v3: filename parsing (local only, never looked up online).
    val titleParsed: String? = null,
    val year: Int? = null,
    val season: Int? = null,
    val episode: Int? = null,
)

/**
 * Full-text index over files, kept in sync with `media_files` by triggers
 * Room generates (an "external content" FTS table: the text lives in the
 * content table, this holds only the index). unicode61 folds accents, so
 * "samourai" finds "Samouraï".
 */
@Fts4(contentEntity = MediaFileEntity::class, tokenizer = FtsOptions.TOKENIZER_UNICODE61)
@Entity(tableName = "media_fts")
data class MediaFtsEntity(
    val name: String,
    val titleParsed: String?,
    val relPath: String,
)

@Fts4(contentEntity = FolderEntity::class, tokenizer = FtsOptions.TOKENIZER_UNICODE61)
@Entity(tableName = "folder_fts")
data class FolderFtsEntity(
    val name: String,
    val titleParsed: String?,
    val relPath: String,
)

/**
 * One walk of one share (guardrail G3: progress is a row, not a callback).
 * Written by the scan worker a couple of times a second and observed as a
 * Flow by every screen that shows scan state, so they all agree and it
 * survives the process dying.
 */
@Entity(
    tableName = "scan_runs",
    foreignKeys = [ForeignKey(ShareEntity::class, ["id"], ["shareId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("shareId")],
)
data class ScanRunEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val shareId: Long,
    /** RUNNING, DONE, FAILED or CANCELLED. */
    val status: String,
    val foldersDone: Int,
    val filesFound: Int,
    /** The path being read right now, for the Scanning screen's "READING" line. */
    val currentPath: String,
    val startedAtMs: Long,
    val finishedAtMs: Long?,
    val error: String?,
) {
    companion object {
        const val RUNNING = "RUNNING"
        const val DONE = "DONE"
        const val FAILED = "FAILED"
        const val CANCELLED = "CANCELLED"
    }
}

/** Queries the user typed, newest first, for the Search screen's "Recent". */
@Entity(tableName = "recent_searches")
data class RecentSearchEntity(
    @PrimaryKey val query: String,
    val searchedAtMs: Long,
)

/**
 * One cached image (guardrail G5). The bytes live in the app's own
 * directory at [relPath]; this row says where they came from and whether
 * the resolver gave up. Keyed by what it belongs to, so a rescan that keeps
 * the file id keeps its artwork.
 */
@Entity(
    tableName = "artwork",
    indices = [Index(value = ["ownerType", "ownerId", "ownerVariant", "kind"], unique = true)],
)
data class ArtworkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** "file", "folder" or "moment" ([com.regolith.domain.artwork.ArtworkOwner.typeName]). */
    val ownerType: String,
    val ownerId: Long,
    /**
     * [com.regolith.domain.artwork.ArtworkOwner.variant]: which of several
     * images for this owner, and "" for the owners that have only one.
     *
     * Schema v11 added it, with "" as the default, so every row written
     * before it keeps its identity — a file's thumb was and remains
     * ("file", id, "", "THUMB"). It is in the unique index because a film
     * carries one frame per named chapter and they must not collide.
     */
    @ColumnInfo(defaultValue = "")
    val ownerVariant: String = "",
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

/**
 * One download (design section 05, "On this device"). One row per file;
 * the bytes land in the app's own directory at [localPath]. [bytesDone]
 * is what makes a transfer resumable: the worker reopens the share at
 * that offset and appends (guardrail G3: progress is a row).
 */
@Entity(
    tableName = "transfers",
    foreignKeys = [ForeignKey(MediaFileEntity::class, ["id"], ["fileId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["fileId"], unique = true), Index("status")],
)
data class TransferEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val fileId: Long,
    /** [com.regolith.domain.transfer.TransferStatus] name. */
    val status: String,
    val bytesDone: Long,
    val totalBytes: Long,
    /** Path under the downloads directory, e.g. "12.mkv". */
    val localPath: String,
    /** [com.regolith.domain.transfer.TransferCause] name, for PAUSED and FAILED. */
    val cause: String?,
    /** "12.1 GB needed": the cause's number, when there is one. */
    val causeBytes: Long?,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    val finishedAtMs: Long?,
)

/**
 * Schema v6: a folder the user picked for download, waiting to be walked.
 *
 * A selection is transient and lives in memory, but a PICKED FOLDER is not
 * the same thing as a selection: by the time the user has tapped Download
 * the app has promised to fetch everything inside it, and that promise has
 * to survive the process being killed mid-walk. A folder that was never
 * scanned has no `media_files` rows to expand into, so the walk over SMB is
 * the only way to learn what is in it — and a walk that forgot its own
 * queue on a restart would leave the download half-done with nothing
 * recording what was still owed.
 *
 * Rows are written by `TransferRepository.addFolderPicks`, consumed by
 * `TransferQueueWorker`'s discovery phase, and cleared when the queue
 * drains. Web analogy: a job table, not a shopping cart.
 */
@Entity(
    tableName = "download_picks",
    foreignKeys = [ForeignKey(FolderEntity::class, ["id"], ["folderId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["folderId"], unique = true)],
)
data class DownloadPickEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val folderId: Long,
    val shareId: Long,
    /** `/`-separated, no leading slash. Kept so the walk can honour the share's roots. */
    val relPath: String,
    /** False until the subtree has been listed off the share. */
    val discovered: Boolean = false,
    /** How many playable files the walk found. Meaningless until [discovered]. */
    val filesFound: Int = 0,
    val createdAtMs: Long,
    // --- Schema v7: "this folder, minus these".
    /**
     * File ids the user took back out of this pick, comma-joined. A column
     * rather than a child table because a pick lives for one drain and is
     * then deleted; a table would outlive the only thing it describes.
     */
    @ColumnInfo(defaultValue = "") val excludedFileIds: String = "",
    /** Subfolder relPaths taken back out, newline-joined. The walk does not enter them. */
    @ColumnInfo(defaultValue = "") val excludedPaths: String = "",
)

/**
 * Schema v8: one chapter the user wrote for one file.
 *
 * Keyed by the file's Room id, which is stable across rescans (guardrail
 * G3) and goes away with the share — the same footing as playback
 * progress. A file's chapters are always written as a set (see
 * [UserChapterDao.replaceForFile]); there is no editing one row in place,
 * because a chapter's meaning depends on its neighbours.
 */
@Entity(
    tableName = "user_chapters",
    foreignKeys = [ForeignKey(MediaFileEntity::class, ["id"], ["fileId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["fileId", "startMs"], unique = true)],
)
data class UserChapterEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val fileId: Long,
    val startMs: Long,
    /** Null when the user placed a mark without naming it; shows as "Part n". */
    val title: String?,
    val updatedAtMs: Long,
)

/**
 * Full-text index over the names the user gave their chapters, so Search
 * can find a film by a moment in it ("the heist"). External content like
 * [MediaFtsEntity]; only the title is indexed, so an unnamed mark is not
 * findable — it has no words to find.
 */
@Fts4(contentEntity = UserChapterEntity::class, tokenizer = FtsOptions.TOKENIZER_UNICODE61)
@Entity(tableName = "user_chapter_fts")
data class UserChapterFtsEntity(
    val title: String?,
)

/**
 * Schema v9: where a film's user chapters stand against the sidecar on the
 * share (P10). The file is the durable copy; the rows in `user_chapters`
 * are its cache. [shareMtimeMs] is the modified time last imported or
 * written, [dirty] means the phone has edits the share has not, [origin]
 * says who wrote the rows (LOCAL or SHARE), and [note] carries a one-time
 * message for the sheet or the READ_ONLY marker while a write is refused.
 */
@Entity(
    tableName = "chapter_sync",
    foreignKeys = [ForeignKey(MediaFileEntity::class, ["id"], ["fileId"], onDelete = ForeignKey.CASCADE)],
)
data class ChapterSyncEntity(
    @PrimaryKey val fileId: Long,
    val shareMtimeMs: Long?,
    val dirty: Boolean,
    val origin: String,
    val note: String?,
    val updatedAtMs: Long,
)
