package com.regolith.player

import androidx.media3.common.ForwardingSimpleBasePlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * The player as the media session shows it (on the lock screen, in the
 * notification shade, in the picture-in-picture window) with Previous and
 * Next stepping through Regolith's own running order, the way the player's
 * and the mini player's buttons do.
 *
 * ExoPlayer only ever holds the one film: [PlaybackSession] loads the next
 * itself, so the order can be a folder, a Play all or a repeat. Left alone,
 * the session would grey Next out and make Previous restart the film.
 * Everything else passes straight through to the ExoPlayer underneath, and
 * so do Previous and Next while a Moments reel plays: its clips are real
 * items in ExoPlayer's list. Web analogy: a proxy in front of the real player
 * that rewrites two routes.
 *
 * @param steps what plays before and after this film, read fresh each time.
 * @param go opens a film, as the player's own Previous and Next do.
 */
@UnstableApi
internal class SessionPlayer(
    player: Player,
    private val steps: () -> PlaybackState,
    private val go: (fileId: Long) -> Unit,
) : ForwardingSimpleBasePlayer(player) {

    override fun getState(): State {
        val state = super.getState()
        if (steps().reel != null) return state
        val back = steps().upPrevious != null
        val on = steps().upNext != null
        val commands = state.availableCommands.buildUpon()
            .removeAll(PREVIOUS, PREVIOUS_ITEM, NEXT, NEXT_ITEM)
            .addIf(PREVIOUS, back).addIf(PREVIOUS_ITEM, back)
            .addIf(NEXT, on).addIf(NEXT_ITEM, on)
            .build()
        return state.buildUpon().setAvailableCommands(commands).build()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        if (steps().reel != null) return super.handleSeek(mediaItemIndex, positionMs, seekCommand)
        val to = when (seekCommand) {
            NEXT, NEXT_ITEM -> steps().upNext
            PREVIOUS, PREVIOUS_ITEM -> steps().upPrevious
            else -> return super.handleSeek(mediaItemIndex, positionMs, seekCommand)
        }
        to?.let { go(it.fileId) }
        return Futures.immediateVoidFuture()
    }

    /** The running order changed under the film: the buttons are worked out again. */
    fun stepsChanged() = invalidateState()

    private companion object {
        const val PREVIOUS = Player.COMMAND_SEEK_TO_PREVIOUS
        const val PREVIOUS_ITEM = Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM
        const val NEXT = Player.COMMAND_SEEK_TO_NEXT
        const val NEXT_ITEM = Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM
    }
}
