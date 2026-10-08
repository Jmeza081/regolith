package com.regolith.ui.poster

import android.graphics.Bitmap
import com.regolith.domain.artwork.PosterTarget
import com.regolith.ui.components.PosterSwap

/**
 * Everything the poster editor draws, as one value (the screen's single
 * source of truth, like a Redux store's state).
 *
 * The crop box's framing is NOT here: it changes on every pixel of a pinch
 * and belongs to the screen, the way a scroll position would. The screen
 * hands it to [PosterEditorViewModel.save] when it matters.
 */
data class PosterEditorUiState(
    /** The film's title, under the screen title. */
    val title: String = "",
    val durationMs: Long = 0,
    /** Where the poster would be written; null until known, and for films that have nowhere to write it. */
    val target: PosterTarget? = null,
    /** The time last asked for: what the clock field and scrubber show. */
    val positionMs: Long = 0,
    /** The full-resolution frame on screen, or null before the first arrives. */
    val frame: Bitmap? = null,
    /** True while a frame is being decoded; the previous one stays on screen underneath. */
    val loadingFrame: Boolean = true,
    /** The last frame request came back empty. */
    val frameFailed: Boolean = false,
    /** How far "one frame" moves, from the film's frame rate (24 fps when it does not say). */
    val frameStepMs: Long = DEFAULT_FRAME_STEP_MS,
    val saving: Boolean = false,
    /**
     * The folder has a poster already: the sheet that asks first, showing it
     * and the dated name it will be kept under beside the new one.
     */
    val swap: PosterSwap? = null,
    /** Why the last save did not happen, in words for the user. */
    val saveError: String? = null,
    /** Saved: the screen closes itself. */
    val done: Boolean = false,
) {
    val canSave: Boolean get() = frame != null && target != null && !saving && !loadingFrame

    companion object {
        const val DEFAULT_FRAME_STEP_MS = 42L
    }
}
