package com.regolith.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.regolith.domain.library.LibrarySort
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Small key-value settings (flags, toggles). DataStore is the modern
 * replacement for SharedPreferences: async, transactional, exposed as Flows.
 *
 * Web analogy: localStorage, but every read is an observable.
 * Structured data (servers, files, progress) goes in Room, not here.
 */
@Singleton
class AppPreferences @Inject constructor(
    private val store: DataStore<Preferences>,
) {
    private object Keys {
        val onboardingDone = booleanPreferencesKey("onboarding_done")
        val hardwareDecoding = booleanPreferencesKey("hardware_decoding")
        val scrubThumbnails = booleanPreferencesKey("scrub_thumbnails")
        val librarySort = stringPreferencesKey("library_sort")
        val gesturesSeen = booleanPreferencesKey("player_gestures_seen")
    }

    /** The player's gesture map is shown once, the first time the player opens. */
    val gesturesSeen: Flow<Boolean> = store.data.map { it[Keys.gesturesSeen] ?: false }

    suspend fun setGesturesSeen() {
        store.edit { it[Keys.gesturesSeen] = true }
    }

    val onboardingDone: Flow<Boolean> = store.data.map { it[Keys.onboardingDone] ?: false }

    suspend fun setOnboardingDone(done: Boolean) {
        store.edit { it[Keys.onboardingDone] = done }
    }

    /** Settings › Playback › Hardware decoding. Also what the playback sheet's Decoder choice writes. */
    val hardwareDecoding: Flow<Boolean> = store.data.map { it[Keys.hardwareDecoding] ?: true }

    suspend fun setHardwareDecoding(enabled: Boolean) {
        store.edit { it[Keys.hardwareDecoding] = enabled }
    }

    /** Settings › Playback › Scrub thumbnails. Consumed in Phase 3. */
    val scrubThumbnails: Flow<Boolean> = store.data.map { it[Keys.scrubThumbnails] ?: true }

    suspend fun setScrubThumbnails(enabled: Boolean) {
        store.edit { it[Keys.scrubThumbnails] = enabled }
    }

    /** Library › Sort by. Remembered, like a column sort in a web table. */
    val librarySort: Flow<LibrarySort> = store.data.map { p -> p[Keys.librarySort]?.let { runCatching { LibrarySort.valueOf(it) }.getOrNull() } ?: LibrarySort.NAME }

    suspend fun setLibrarySort(sort: LibrarySort) {
        store.edit { it[Keys.librarySort] = sort.name }
    }
}
