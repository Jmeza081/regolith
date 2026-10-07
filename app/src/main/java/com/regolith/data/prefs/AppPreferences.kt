package com.regolith.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.regolith.domain.display.NavHideAfter
import com.regolith.domain.display.PostersPerRow
import com.regolith.domain.library.LibraryOrder
import com.regolith.domain.library.LibrarySort
import com.regolith.domain.library.MomentOrder
import com.regolith.domain.library.MomentSort
import com.regolith.domain.library.SortDirection
import com.regolith.domain.playback.AmbientLight
import com.regolith.domain.playback.PlayerOrientation
import com.regolith.domain.playback.RepeatMode
import com.regolith.domain.library.ViewMode
import androidx.datastore.preferences.core.edit
import com.regolith.domain.media.ShortsLength
import com.regolith.domain.security.LockAfter
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
        val librarySortDirection = stringPreferencesKey("library_sort_direction")
        val deviceSort = stringPreferencesKey("device_sort")
        val deviceSortDirection = stringPreferencesKey("device_sort_direction")
        val momentSort = stringPreferencesKey("moment_sort")
        val momentSortDirection = stringPreferencesKey("moment_sort_direction")
        val gesturesSeen = booleanPreferencesKey("player_gestures_seen")
        val libraryViewMode = stringPreferencesKey("library_view_mode")
        val browseViewMode = stringPreferencesKey("browse_view_mode")
        val deviceViewMode = stringPreferencesKey("device_view_mode")
        val searchViewMode = stringPreferencesKey("search_view_mode")
        val autoplayNext = booleanPreferencesKey("autoplay_next")
        val autoplayImmediately = booleanPreferencesKey("autoplay_immediately")
        val autoHideRail = booleanPreferencesKey("auto_hide_rail")
        val navHideAfter = stringPreferencesKey("nav_hide_after")
        val postersPerRow = stringPreferencesKey("posters_per_row")
        val titleParserVersion = intPreferencesKey("title_parser_version")
        val spoofMode = booleanPreferencesKey("spoof_mode")
        val spoofSalt = longPreferencesKey("spoof_salt")
        val railHidden = booleanPreferencesKey("rail_hidden")
        val playerOrientation = stringPreferencesKey("player_orientation")
        val playerRepeat = stringPreferencesKey("player_repeat")
        /** The old on/off switch, from before Color bleed. Read once for its answer, then superseded by [ambientLightMode]. */
        val ambientLight = booleanPreferencesKey("ambient_light")
        val ambientLightMode = stringPreferencesKey("ambient_light_mode")
        val appLock = booleanPreferencesKey("app_lock")
        val appLockAfter = stringPreferencesKey("app_lock_after")
        val shortsAutoAdvance = booleanPreferencesKey("shorts_auto_advance")
        val shortsLength = stringPreferencesKey("shorts_length")
        val hiddenPhoneFolders = stringSetPreferencesKey("hidden_phone_folders")
        val hiddenPhoneFiles = stringSetPreferencesKey("hidden_phone_files")
    }

    /** Settings › Privacy: ask for a fingerprint, face or screen lock before showing the library. */
    /**
     * Library › On this device. Its own key beside [libraryViewMode] for the
     * same reason Browse has one: this list is about copies and their state,
     * so it starts as rows, and the choice should not follow the wall's.
     */
    val deviceViewMode: Flow<ViewMode> = store.data.map { it[Keys.deviceViewMode].toViewMode(ViewMode.ROWS) }

    suspend fun setDeviceViewMode(mode: ViewMode) {
        store.edit { it[Keys.deviceViewMode] = mode.name }
    }

    /**
     * Phone-storage folders switched off in Settings, by their path
     * (`storage/emulated/0/WhatsApp/…`). A path, not a row id: the folder's
     * row is rebuilt from MediaStore, and a choice like "never show me
     * WhatsApp" has to outlive that.
     */
    val hiddenPhoneFolders: Flow<Set<String>> = store.data.map { it[Keys.hiddenPhoneFolders] ?: emptySet() }

    suspend fun setPhoneFolderHidden(relPath: String, hidden: Boolean) {
        store.edit { prefs ->
            val now = prefs[Keys.hiddenPhoneFolders] ?: emptySet()
            prefs[Keys.hiddenPhoneFolders] = if (hidden) now + relPath else now - relPath
        }
    }

    /** Single phone videos hidden with Title Detail's "Hide from Regolith", by path, for the same reason. */
    val hiddenPhoneFiles: Flow<Set<String>> = store.data.map { it[Keys.hiddenPhoneFiles] ?: emptySet() }

    suspend fun setPhoneFileHidden(relPath: String, hidden: Boolean) {
        store.edit { prefs ->
            val now = prefs[Keys.hiddenPhoneFiles] ?: emptySet()
            prefs[Keys.hiddenPhoneFiles] = if (hidden) now + relPath else now - relPath
        }
    }

    val appLock: Flow<Boolean> = store.data.map { it[Keys.appLock] ?: false }

    suspend fun setAppLock(enabled: Boolean) {
        store.edit { it[Keys.appLock] = enabled }
    }

    /** How long Regolith may sit in the background before it asks again. */
    val appLockAfter: Flow<LockAfter> = store.data.map { LockAfter.of(it[Keys.appLockAfter]) }

    suspend fun setAppLockAfter(after: LockAfter) {
        store.edit { it[Keys.appLockAfter] = after.name }
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

    /**
     * Shorts › auto-advance. Remembered rather than session-only for the
     * same reason the rotation lock is: a mode you have to set every time
     * you open the feed is not a mode. Off by default — a clip that loops
     * waits for you, and one that moves on decides for you.
     */
    val shortsAutoAdvance: Flow<Boolean> = store.data.map { it[Keys.shortsAutoAdvance] ?: false }

    suspend fun setShortsAutoAdvance(enabled: Boolean) {
        store.edit { it[Keys.shortsAutoAdvance] = enabled }
    }

    /**
     * Settings › Shorts: how long a clip may be and still reach the feed.
     *
     * Stored by enum name rather than a raw number of seconds, so the
     * offered set can change later without stranding anyone on a value that
     * is no longer one of the choices.
     */
    val shortsLength: Flow<ShortsLength> = store.data.map { ShortsLength.of(it[Keys.shortsLength]) }

    suspend fun setShortsLength(length: ShortsLength) {
        store.edit { it[Keys.shortsLength] = length.name }
    }

    /** Settings › Playback › Scrub thumbnails. Consumed in Phase 3. */
    val scrubThumbnails: Flow<Boolean> = store.data.map { it[Keys.scrubThumbnails] ?: true }

    suspend fun setScrubThumbnails(enabled: Boolean) {
        store.edit { it[Keys.scrubThumbnails] = enabled }
    }

    /**
     * Settings › Display › Ambient light: Off, Mirror or Color bleed. Mirror
     * by default — it is most of what the player looks like — but it is a
     * choice rather than a constant because both live lights read the video
     * surface back off the GPU several times a second, and that is battery a
     * phone on a long flight may want back.
     *
     * It used to be a switch. A phone that turned it off before the choice
     * existed is still off; see [AmbientLight.of].
     */
    val ambientLight: Flow<AmbientLight> = store.data.map {
        AmbientLight.of(it[Keys.ambientLightMode], legacyOn = it[Keys.ambientLight])
    }

    suspend fun setAmbientLight(light: AmbientLight) {
        store.edit {
            it[Keys.ambientLightMode] = light.name
            // Answered by name from here on; the old switch has said all it will.
            it.remove(Keys.ambientLight)
        }
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
     * Settings › Display › Auto-hide the navigation. Both shapes: the rail on
     * a wide window and the pill on a phone go away on the same timer.
     * Controls the idle timer only — [railHidden] is the deliberate pin, is
     * independent of this, and counts on a wide window ONLY.
     */
    val autoHideRail: Flow<Boolean> = store.data.map { it[Keys.autoHideRail] ?: true }

    suspend fun setAutoHideRail(enabled: Boolean) {
        store.edit { it[Keys.autoHideRail] = enabled }
    }

    /**
     * Settings › Display › Hide after: how long that idle timer waits. Its
     * own key rather than folded into [autoHideRail] as an "Off" choice, so
     * turning the switch off and on again brings back the time you picked.
     */
    val navHideAfter: Flow<NavHideAfter> = store.data.map { NavHideAfter.of(it[Keys.navHideAfter]) }

    suspend fun setNavHideAfter(after: NavHideAfter) {
        store.edit { it[Keys.navHideAfter] = after.name }
    }

    /**
     * Settings › Display › Posters per row: how many across the Library's
     * walls on the inner display. Read by the walls through LibraryViewModel.
     */
    val postersPerRow: Flow<PostersPerRow> = store.data.map { PostersPerRow.of(it[Keys.postersPerRow]) }

    suspend fun setPostersPerRow(perRow: PostersPerRow) {
        store.edit { it[Keys.postersPerRow] = perRow.name }
    }

    /**
     * The [com.regolith.domain.library.TitleParser.VERSION] every stored
     * parse is at. Behind it, `LibraryRepository.ensureNamesParsed` re-parses
     * the names once. 0 on an install that has never checked.
     */
    val titleParserVersion: Flow<Int> = store.data.map { it[Keys.titleParserVersion] ?: 0 }

    suspend fun setTitleParserVersion(version: Int) {
        store.edit { it[Keys.titleParserVersion] = version }
    }

    /**
     * Settings › Demo › Spoof mode, with the salt its made-up names and
     * stand-in photos are worked out from (`SpoofMode`). Null while it is off.
     * The salt is picked the first time it is switched on and kept, so a
     * video keeps the same made-up name every time it is switched back on.
     */
    val spoofSalt: Flow<Long?> = store.data.map { prefs -> if (prefs[Keys.spoofMode] == true) prefs[Keys.spoofSalt] else null }

    suspend fun setSpoofMode(enabled: Boolean) {
        store.edit { prefs ->
            if (enabled && prefs[Keys.spoofSalt] == null) prefs[Keys.spoofSalt] = java.security.SecureRandom().nextLong()
            prefs[Keys.spoofMode] = enabled
        }
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

    /**
     * The player's repeat button. Remembered for the same reason the
     * rotation lock is: someone who watches a folder on a loop means it
     * about the folder, not about one file.
     */
    val playerRepeat: Flow<RepeatMode> = store.data.map { p ->
        p[Keys.playerRepeat]?.let { runCatching { RepeatMode.valueOf(it) }.getOrNull() } ?: RepeatMode.OFF
    }

    suspend fun setPlayerRepeat(mode: RepeatMode) {
        store.edit { it[Keys.playerRepeat] = mode.name }
    }

    /**
     * Library › Sort by, and which way it runs. Remembered, like a column
     * sort in a web table. A sort saved before direction existed has no
     * direction stored, and reads back in its natural one, which is what
     * it always did.
     */
    val libraryOrder: Flow<LibraryOrder> = store.data.map { p ->
        val sort = p[Keys.librarySort]?.let { runCatching { LibrarySort.valueOf(it) }.getOrNull() } ?: LibrarySort.NAME
        val direction = p[Keys.librarySortDirection]?.let { runCatching { SortDirection.valueOf(it) }.getOrNull() } ?: sort.natural
        LibraryOrder(sort, direction)
    }

    suspend fun setLibraryOrder(order: LibraryOrder) {
        store.edit {
            it[Keys.librarySort] = order.sort.name
            it[Keys.librarySortDirection] = order.direction.name
        }
    }

    /**
     * The device tab's order: its downloads and the phone's own videos.
     * Its own, because the tab is a different list with a different
     * question behind it ("what did I just keep?"), so it starts at Date
     * added, newest first, which is how the tab always read.
     */
    val deviceOrder: Flow<LibraryOrder> = store.data.map { p ->
        val sort = p[Keys.deviceSort]?.let { runCatching { LibrarySort.valueOf(it) }.getOrNull() } ?: LibraryOrder.DEVICE_DEFAULT.sort
        val direction = p[Keys.deviceSortDirection]?.let { runCatching { SortDirection.valueOf(it) }.getOrNull() } ?: sort.natural
        LibraryOrder(sort, direction)
    }

    suspend fun setDeviceOrder(order: LibraryOrder) {
        store.edit {
            it[Keys.deviceSort] = order.sort.name
            it[Keys.deviceSortDirection] = order.direction.name
        }
    }

    /** A collection profile's Moments tab: one order for every profile, as the wall has one for every wall. */
    val momentOrder: Flow<MomentOrder> = store.data.map { p ->
        val sort = p[Keys.momentSort]?.let { runCatching { MomentSort.valueOf(it) }.getOrNull() } ?: MomentSort.VIDEO
        val direction = p[Keys.momentSortDirection]?.let { runCatching { SortDirection.valueOf(it) }.getOrNull() } ?: sort.natural
        MomentOrder(sort, direction)
    }

    suspend fun setMomentOrder(order: MomentOrder) {
        store.edit {
            it[Keys.momentSort] = order.sort.name
            it[Keys.momentSortDirection] = order.direction.name
        }
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

    /**
     * Search's grid/rows switch: one choice for both result groups (points of
     * interest and matches). Rows are the default, because results are read
     * name by name and only the rows show which run of the name matched.
     */
    val searchViewMode: Flow<ViewMode> = store.data.map { it[Keys.searchViewMode].toViewMode(ViewMode.ROWS) }

    suspend fun setSearchViewMode(mode: ViewMode) {
        store.edit { it[Keys.searchViewMode] = mode.name }
    }

    /** An unknown or missing stored name falls back rather than throwing (the enum may gain cases). */
    private fun String?.toViewMode(fallback: ViewMode) =
        this?.let { runCatching { ViewMode.valueOf(it) }.getOrNull() } ?: fallback
}
