package com.regolith.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.regolith.domain.library.LibrarySort
import com.regolith.domain.playback.PlayerOrientation
import com.regolith.domain.library.ViewMode
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
        val libraryViewMode = stringPreferencesKey("library_view_mode")
        val browseViewMode = stringPreferencesKey("browse_view_mode")
        val autoplayNext = booleanPreferencesKey("autoplay_next")
        val autoplayImmediately = booleanPreferencesKey("autoplay_immediately")
        val autoHideRail = booleanPreferencesKey("auto_hide_rail")
        val railHidden = booleanPreferencesKey("rail_hidden")
        val playerOrientation = stringPreferencesKey("player_orientation")
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

    /**
     * Settings › Playback › Keep playing. On by default: a folder of
     * episodes is the common case, and the Up next card gives you ten
     * seconds to say no.
     */
    val autoplayNext: Flow<Boolean> = store.data.map { it[Keys.autoplayNext] ?: true }

    suspend fun setAutoplayNext(enabled: Boolean) {
        store.edit { it[Keys.autoplayNext] = enabled }
    }

    /**
     * Settings › Playback › Don't ask first. Skips the Up next card and its
     * countdown, so the next file simply starts. Off by default, and
     * meaningless on its own: the player reads it only when [autoplayNext]
     * is on, because you cannot skip a question you are not being asked.
     */
    val autoplayImmediately: Flow<Boolean> = store.data.map { it[Keys.autoplayImmediately] ?: false }

    suspend fun setAutoplayImmediately(enabled: Boolean) {
        store.edit { it[Keys.autoplayImmediately] = enabled }
    }

    /**
     * Settings › Display › Auto-hide the rail. Wide windows only; on a phone
     * the pill is the bottom bar and never retracts. Controls the idle timer
     * only — [railHidden] is the deliberate pin and is independent of it.
     */
    val autoHideRail: Flow<Boolean> = store.data.map { it[Keys.autoHideRail] ?: true }

    suspend fun setAutoHideRail(enabled: Boolean) {
        store.edit { it[Keys.autoHideRail] = enabled }
    }

    /**
     * The rail pinned away by its chevron: the layout gives its width back to
     * the screen until the spine is tapped. Remembered, unlike the idle
     * retract, because it is a choice about how much room the nav deserves.
     */
    val railHidden: Flow<Boolean> = store.data.map { it[Keys.railHidden] ?: false }

    suspend fun setRailHidden(hidden: Boolean) {
        store.edit { it[Keys.railHidden] = hidden }
    }

    /**
     * The player's rotation lock. Remembered: a lock you have to set for
     * every film is not a lock. An unknown stored name falls back to AUTO
     * rather than throwing, as the other enums here do.
     */
    val playerOrientation: Flow<PlayerOrientation> = store.data.map { p ->
        p[Keys.playerOrientation]?.let { runCatching { PlayerOrientation.valueOf(it) }.getOrNull() } ?: PlayerOrientation.AUTO
    }

    suspend fun setPlayerOrientation(orientation: PlayerOrientation) {
        store.edit { it[Keys.playerOrientation] = orientation.name }
    }

    /** Library › Sort by. Remembered, like a column sort in a web table. */
    val librarySort: Flow<LibrarySort> = store.data.map { p -> p[Keys.librarySort]?.let { runCatching { LibrarySort.valueOf(it) }.getOrNull() } ?: LibrarySort.NAME }

    suspend fun setLibrarySort(sort: LibrarySort) {
        store.edit { it[Keys.librarySort] = sort.name }
    }

    /**
     * Library's grid/rows switch. The poster wall is the design's default,
     * so GRID is what a fresh install gets. Browse keeps its own choice
     * ([browseViewMode]) because the two lists answer different questions:
     * "what have I got" vs "what is in this folder".
     */
    val libraryViewMode: Flow<ViewMode> = store.data.map { it[Keys.libraryViewMode].toViewMode(ViewMode.GRID) }

    suspend fun setLibraryViewMode(mode: ViewMode) {
        store.edit { it[Keys.libraryViewMode] = mode.name }
    }

    /** Browse's grid/rows switch. Rows are the design's default here: a folder listing reads as a list. */
    val browseViewMode: Flow<ViewMode> = store.data.map { it[Keys.browseViewMode].toViewMode(ViewMode.ROWS) }

    suspend fun setBrowseViewMode(mode: ViewMode) {
        store.edit { it[Keys.browseViewMode] = mode.name }
    }

    /** An unknown or missing stored name falls back rather than throwing (the enum may gain cases). */
    private fun String?.toViewMode(fallback: ViewMode) =
        this?.let { runCatching { ViewMode.valueOf(it) }.getOrNull() } ?: fallback
}
