package com.regolith.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * The on-device database. Version 1 = Phase 1 tables. Every later schema
 * change bumps the version and ships a Migration; the app never loses the
 * user's progress to a destructive rebuild.
 */
@Database(
    entities = [
        ServerEntity::class,
        ShareEntity::class,
        FolderEntity::class,
        MediaFileEntity::class,
        PlaybackProgressEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class RegolithDatabase : RoomDatabase() {
    abstract fun serverDao(): ServerDao
    abstract fun shareDao(): ShareDao
    abstract fun folderDao(): FolderDao
    abstract fun mediaFileDao(): MediaFileDao
    abstract fun playbackProgressDao(): PlaybackProgressDao
}
