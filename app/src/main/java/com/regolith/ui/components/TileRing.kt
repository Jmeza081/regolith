package com.regolith.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import com.regolith.ui.theme.RegolithTheme
import kotlinx.coroutines.delay

/**
 * The ring a screen puts on one video for a moment as it opens: "here is the
 * one you came for". Browse rings the clip Shorts' Locate left, and both
 * Browse and the Library ring the video whose page opened them from its Path
 * line. On at once, held, then faded.
 *
 * A tile wears it as [MediaTile]'s `highlight`, read from [on]; a row has no
 * art to ring, so it takes [rowTint] as its background instead. Reading [on]
 * for every video is cheap: only the ringed one follows the fade frame by frame.
 */
@Stable
class TileRing internal constructor() {
    private val alpha = Animatable(0f)
    private var ringed by mutableStateOf<Long?>(null)

    /** How strongly [fileId] is ringed right now, 0..1: zero for every video but the ringed one. */
    fun on(fileId: Long): Float = if (fileId == ringed) alpha.value else 0f

    /** The ring as a row's background: a wash of ink, at most [ROW_TINT] strong. */
    @Composable
    fun rowTint(fileId: Long): Color = RegolithTheme.colors.ink.copy(alpha = on(fileId) * ROW_TINT)

    /**
     * Rings [fileId] and returns once the ring has faded. Cut short, it goes
     * at once rather than staying on: the caller may ring it again.
     */
    suspend fun ring(fileId: Long) {
        ringed = fileId
        try {
            alpha.snapTo(1f)
            delay(HOLD_MS)
            alpha.animateTo(0f, tween(FADE_MS))
        } finally {
            if (ringed == fileId) ringed = null
        }
    }

    private companion object {
        /** Long enough to find with your eye, short enough not to look stuck. */
        const val HOLD_MS = 1_200L
        const val FADE_MS = 500

        /** As strong as a row's wash of ink gets. */
        const val ROW_TINT = 0.18f
    }
}

/** A [TileRing] for this screen, ringing nothing until [TileRing.ring] is called. */
@Composable
fun rememberTileRing(): TileRing = remember { TileRing() }
