package com.regolith.player

import android.content.Context
import android.content.pm.PackageManager
import com.regolith.data.db.MediaFileEntity
import com.regolith.data.prefs.AppPreferences
import com.regolith.data.repository.LibraryRepository
import com.regolith.data.repository.SourceRepository
import com.regolith.domain.media.ShortsDeck
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The next Shorts deck, dealt before the tab is opened.
 *
 * Every arrival at Shorts deals a fresh shuffle, and so does tapping the tab
 * while on it. If the seed for that shuffle is chosen NOW rather than then,
 * the clips the next visit will open on are known now — and what they need
 * can be made while you are elsewhere in the app. Today that is their
 * strips ([ShortsFrames]): held sideways, the first clip's frames are there
 * the moment the tab opens instead of filling in over several seconds.
 *
 * [take] hands over the seed that was warmed and starts warming the one
 * after it, so each visit opens on a deck prepared during the last.
 *
 * Only on a device with a screen big enough for the sideways panel: a
 * tablet, or a foldable (whose inner display may be folded away right now —
 * which is exactly when the warming is worth doing). Anywhere else the strip
 * is never shown, and making it would be reads for nothing.
 */
@Singleton
class ShortsWarmup @Inject constructor(
    @ApplicationContext private val context: Context,
    private val library: LibraryRepository,
    private val sources: SourceRepository,
    private val prefs: AppPreferences,
    private val frames: ShortsFrames,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile private var upcoming = newSeed()
    private var warming: Job? = null

    /**
     * Warm the first deck, and warm again whenever what a deck is dealt
     * from changes (a share switched on, the length setting). Once, at
     * startup.
     */
    fun start() {
        if (!canShowPanel()) return
        scope.launch {
            combine(sources.observeEnabledShares(), prefs.shortsLength) { shares, length -> shares.map { it.id } to length }
                .distinctUntilChanged()
                .collect { warm(upcoming) }
        }
    }

    /** The seed to deal a deck with now. The deck after it starts warming at once. */
    fun take(): Long {
        val seed = upcoming
        upcoming = newSeed()
        if (canShowPanel()) warm(upcoming)
        return seed
    }

    // Called from the startup collector and from the tab, on different threads.
    @Synchronized
    private fun warm(seed: Long) {
        warming?.cancel()
        warming = scope.launch {
            val shares = sources.observeEnabledShares().first()
            if (shares.isEmpty()) return@launch
            val files = library.observeShorts(shares.map { it.id }, prefs.shortsLength.first().maxMs).first()
            // Dealt exactly as the feed deals it — same query, same order,
            // same seed — so these ARE the clips the next visit opens on.
            val opening = ShortsDeck.deal(files, seed, emptySet()) { it.id }.take(WARM_CLIPS)
            frames.warmAhead(opening.map { it.toShortsClip() })
        }
    }

    private fun canShowPanel(): Boolean =
        context.resources.configuration.smallestScreenWidthDp >= LARGE_SCREEN_DP ||
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_SENSOR_HINGE_ANGLE)

    private fun newSeed() = System.nanoTime()

    private companion object {
        /** The first five: the clip it opens on and the four you are most likely to swipe to. */
        const val WARM_CLIPS = 5
        const val LARGE_SCREEN_DP = 600
    }
}

/** A library row, as its Shorts strip knows it. */
internal fun MediaFileEntity.toShortsClip() = ShortsClip(id, sizeBytes, modifiedAtMs, durationMs ?: 0)
