package com.regolith.player

import android.content.Intent
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * The film, published to the rest of Android: the media session that
 * [PlaybackSession] keeps over its player, shown as a notification with its
 * controls, on the lock screen, to headset buttons and in the
 * picture-in-picture window. A foreground service while it plays, which is
 * also what keeps the app alive when the sound carries on with the screen off.
 *
 * It holds nothing of its own. The session is [PlaybackSession]'s, made and
 * released beside the player it wraps so it can never outlive it; this
 * service is started when a film loads and stopped when it stops. Web
 * analogy: `navigator.mediaSession`, plus a service worker keeping the tab
 * alive.
 */
@UnstableApi
@AndroidEntryPoint
class PlaybackService : MediaSessionService() {

    @Inject lateinit var playback: PlaybackSession

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // A session made outside the service is published by adding it.
        playback.mediaSession?.let { if (!isSessionAdded(it)) addSession(it) }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = playback.mediaSession

    /** Swiping Regolith out of the recent apps ends the film with it. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        playback.stop()
        stopSelf()
    }
}
