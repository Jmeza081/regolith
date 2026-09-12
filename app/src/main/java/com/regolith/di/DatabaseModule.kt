package com.regolith.di

import android.content.Context
import androidx.room.Room
import com.regolith.data.db.ArtworkDao
import com.regolith.data.db.DownloadPickDao
import com.regolith.data.db.FolderDao
import com.regolith.data.db.MediaFileDao
import com.regolith.data.db.PlaybackProgressDao
import com.regolith.data.db.RecentSearchDao
import com.regolith.data.db.ScanRunDao
import com.regolith.data.db.RegolithDatabase
import com.regolith.data.db.ServerDao
import com.regolith.data.db.ShareDao
import com.regolith.data.db.ShareRootDao
import com.regolith.data.db.TransferDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** The Room database and its DAOs as injectable singletons. */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): RegolithDatabase =
        Room.databaseBuilder(context, RegolithDatabase::class.java, "regolith.db")
            // No fallbackToDestructiveMigration: losing progress silently is worse than a crash we hear about.
            .build()

    @Provides fun provideServerDao(db: RegolithDatabase): ServerDao = db.serverDao()
    @Provides fun provideShareDao(db: RegolithDatabase): ShareDao = db.shareDao()
    @Provides fun provideFolderDao(db: RegolithDatabase): FolderDao = db.folderDao()
    @Provides fun provideMediaFileDao(db: RegolithDatabase): MediaFileDao = db.mediaFileDao()
    @Provides fun providePlaybackProgressDao(db: RegolithDatabase): PlaybackProgressDao = db.playbackProgressDao()
    @Provides fun provideArtworkDao(db: RegolithDatabase): ArtworkDao = db.artworkDao()
    @Provides fun provideScanRunDao(db: RegolithDatabase): ScanRunDao = db.scanRunDao()
    @Provides fun provideRecentSearchDao(db: RegolithDatabase): RecentSearchDao = db.recentSearchDao()
    @Provides fun provideTransferDao(db: RegolithDatabase): TransferDao = db.transferDao()
    @Provides fun provideShareRootDao(db: RegolithDatabase): ShareRootDao = db.shareRootDao()
    @Provides fun provideDownloadPickDao(db: RegolithDatabase): DownloadPickDao = db.downloadPickDao()
}
