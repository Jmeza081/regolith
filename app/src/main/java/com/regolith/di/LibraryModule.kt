package com.regolith.di

import com.regolith.data.repository.FolderLookup
import com.regolith.data.repository.LibraryRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** The library's reads, behind the narrow interface screens' file actions ask through. */
@Module
@InstallIn(SingletonComponent::class)
abstract class LibraryModule {
    @Binds abstract fun bindFolderLookup(impl: LibraryRepository): FolderLookup
}
