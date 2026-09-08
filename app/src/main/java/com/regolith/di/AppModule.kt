package com.regolith.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

// One DataStore file for the whole app. Declared at top level because the
// delegate must be a singleton per file name; two instances would corrupt it.
private val Context.appPreferencesStore: DataStore<Preferences> by preferencesDataStore(name = "regolith_prefs")

/**
 * Hilt module: tells the DI container how to build things it cannot construct
 * on its own (third-party types with no `@Inject` constructor).
 * Web analogy: the providers array / factory registrations of a DI container.
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun providePreferencesDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        context.appPreferencesStore
}
