package com.regolith.data.db

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * The on-device database. Every schema change bumps the version and ships
 * a migration; the app never loses the user's progress to a destructive
 * rebuild.
 *
 * Versions:
 *  1. Phase 1 tables.
 *  2. Phase 3: `artwork` table; probe columns on `media_files`. Additive
 *     only, so Room writes the migration itself from the exported schemas
 *     in `app/schemas/` (an "auto migration").
 */
@Database(
    entities = [
        ServerEntity::class,
        ShareEntity::class,
        FolderEntity::class,
        MediaFileEntity::class,
        PlaybackProgressEntity::class,
        ArtworkEntity::class,
    ],
    version = 2,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2)],
)
abstract class RegolithDatabase : RoomDatabase() {
    abstract fun serverDao(): ServerDao
    abstract fun shareDao(): ShareDao
    abstract fun folderDao(): FolderDao
    abstract fun mediaFileDao(): MediaFileDao
    abstract fun playbackProgressDao(): PlaybackProgressDao
    abstract fun artworkDao(): ArtworkDao
}
