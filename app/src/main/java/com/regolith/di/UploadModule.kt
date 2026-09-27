package com.regolith.di

import com.regolith.data.transfer.ContentPhoneFiles
import com.regolith.data.transfer.PhoneFiles
import com.regolith.data.transfer.UploadScheduler
import com.regolith.data.transfer.WorkManagerUploadScheduler
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Uploads (P16): the two seams the queue talks to instead of Android itself. Tests pass fakes. */
@Module
@InstallIn(SingletonComponent::class)
abstract class UploadModule {
    @Binds abstract fun bindPhoneFiles(impl: ContentPhoneFiles): PhoneFiles
    @Binds abstract fun bindUploadScheduler(impl: WorkManagerUploadScheduler): UploadScheduler
}
