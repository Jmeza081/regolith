package com.regolith.data.db

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.AutoMigrationSpec
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * The on-device database. Every schema change bumps the version and ships
 * a migration; the app never loses the user's progress to a destructive
 * rebuild.
 *
 * Versions:
 *  1. Phase 1 tables.
 *  2. Phase 3: `artwork` table; probe columns on `media_files`.
 *  3. Phase 4: parsed-name columns, folder kinds, `scan_runs`,
 *     `recent_searches`, and the two full-text indexes. Additive, so Room
 *     writes the migrations itself from the exported schemas in
 *     `app/schemas/` (an "auto migration"); v3 adds one step by hand to
 *     fill the new indexes from rows that already exist.
 *  4. Phase 5: `transfers` table; `servers.unreachableSinceMs`.
 *  5. `share_roots`: folders chosen inside a share as the library's roots.
 *  6. `download_picks`: folders picked for download, waiting to be
 *     walked over SMB. Additive.
 *  7. `download_picks.excludedFileIds` / `excludedPaths`: what to leave
 *     out of a picked folder. Additive.
 *  8. `user_chapters` and its full-text index `user_chapter_fts`: the
 *     chapters the user wrote (P9). Additive; the index starts empty
 *     because the table does.
 *  9. `chapter_sync` and `shares.writeChapters` (P10): where each film's
 *     chapters stand against the sidecar file on the share, and whether a
 *     share gets those files at all. Additive.
 * 10. `media_files.rotationDegrees`: the rotation the container asks for,
 *     so a portrait clip stored landscape can be recognised as portrait
 *     (Shorts). Additive; null everywhere until the artwork walk fills it.
 */
@Database(
    entities = [
        ServerEntity::class,
        ShareEntity::class,
        FolderEntity::class,
        MediaFileEntity::class,
        PlaybackProgressEntity::class,
        ArtworkEntity::class,
        MediaFtsEntity::class,
        FolderFtsEntity::class,
        ScanRunEntity::class,
        RecentSearchEntity::class,
        TransferEntity::class,
        ShareRootEntity::class,
        DownloadPickEntity::class,
        UserChapterEntity::class,
        UserChapterFtsEntity::class,
        ChapterSyncEntity::class,
    ],
    version = 10,
    exportSchema = true,
    autoMigrations = [
        AutoMigration(from = 1, to = 2),
        AutoMigration(from = 2, to = 3, spec = RegolithDatabase.RebuildFts::class),
        AutoMigration(from = 3, to = 4),
        AutoMigration(from = 4, to = 5),
        AutoMigration(from = 5, to = 6),
        AutoMigration(from = 6, to = 7),
        AutoMigration(from = 7, to = 8),
        AutoMigration(from = 8, to = 9),
        AutoMigration(from = 9, to = 10),
    ],
)
abstract class RegolithDatabase : RoomDatabase() {
    abstract fun serverDao(): ServerDao
    abstract fun shareDao(): ShareDao
    abstract fun folderDao(): FolderDao
    abstract fun mediaFileDao(): MediaFileDao
    abstract fun playbackProgressDao(): PlaybackProgressDao
    abstract fun artworkDao(): ArtworkDao
    abstract fun scanRunDao(): ScanRunDao
    abstract fun recentSearchDao(): RecentSearchDao
    abstract fun transferDao(): TransferDao
    abstract fun shareRootDao(): ShareRootDao
    abstract fun downloadPickDao(): DownloadPickDao
    abstract fun userChapterDao(): UserChapterDao
    abstract fun chapterSyncDao(): ChapterSyncDao
    abstract fun subtreeDao(): SubtreeDao

    /** An external-content FTS table starts empty; `rebuild` indexes what the content table already holds. */
    class RebuildFts : AutoMigrationSpec {
        override fun onPostMigrate(db: SupportSQLiteDatabase) {
            db.execSQL("INSERT INTO media_fts(media_fts) VALUES('rebuild')")
            db.execSQL("INSERT INTO folder_fts(folder_fts) VALUES('rebuild')")
        }
    }
}
