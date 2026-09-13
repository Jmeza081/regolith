package com.regolith.di

import com.regolith.data.media.ChapterSyncScheduler
import com.regolith.data.media.WorkManagerChapterSyncScheduler
import com.regolith.data.repository.SourceRepository
import com.regolith.domain.smb.CredentialSource
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Chapter sidecars (P10): the two interfaces the sync layer talks to instead of concrete classes. */
@Module
@InstallIn(SingletonComponent::class)
abstract class ChaptersModule {
    @Binds abstract fun bindCredentialSource(impl: SourceRepository): CredentialSource
    @Binds abstract fun bindChapterSyncScheduler(impl: WorkManagerChapterSyncScheduler): ChapterSyncScheduler
}
