package com.regolith.domain.playback

/**
 * Whether a film keeps playing once Regolith can no longer be seen at all —
 * its one Activity stopped. That happens when the app is left with
 * picture-in-picture off, when its floating window is closed, and when the
 * screen goes off. (A floating window is not this: picture-in-picture
 * never stops the Activity, so the film plays on in it regardless.)
 *
 * Only the screen going off keeps it, and only when Settings › Playback ›
 * Play with the screen off says the sound carries on; every other way out
 * pauses it, rather than leaving a film playing to nobody — which is what
 * happened before there was a rule.
 */
object BackgroundPlay {
    fun keepsPlaying(screenOn: Boolean, playWithScreenOff: Boolean): Boolean = !screenOn && playWithScreenOff
}
