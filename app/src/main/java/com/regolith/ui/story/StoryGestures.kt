package com.regolith.ui.story

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput

/**
 * A story's touch on its picture, told apart by how long the finger stays.
 *
 * - A **tap** steps: [onTap] hears whether it landed in the left [backZone]
 *   of the width, which goes back a picture; anywhere else goes on.
 * - A **hold** pauses: [onHold] hears true the moment a finger is down and
 *   false when it lifts, and letting go does nothing more, so the picture
 *   carries on from where it stood.
 *
 * A finger up within [holdMs] is a tap. Compose's own `detectTapGestures`
 * counts any release as a tap unless it is told what a long press is,
 * which is why the first build stepped to the next picture after a hold.
 * A swipe that takes the touch over (the swipe down to close) is neither.
 *
 * The callbacks are read once, when the gesture starts listening: pass
 * ones that do not change, such as a state's setter or a ViewModel's methods.
 */
internal fun Modifier.tapOrHold(
    onHold: (held: Boolean) -> Unit,
    onTap: (back: Boolean) -> Unit,
    backZone: Float = BACK_ZONE,
    holdMs: Long = HOLD_MS,
): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown()
        // The picture stops the moment a finger is on it, as a phone's stories do.
        onHold(true)
        var tap: PointerInputChange? = null
        val quick = withTimeoutOrNull(holdMs) { tap = waitForUpOrCancellation() }
        // Held past holdMs: a pause, over when the finger lifts.
        if (quick == null) waitForUpOrCancellation()
        onHold(false)
        // Let go in time, with no swipe taking over: a tap.
        tap?.let { up ->
            up.consume()
            onTap(up.position.x < size.width * backZone)
        }
    }
}

/** The left part of the screen whose tap goes back a picture; the rest goes on. */
internal const val BACK_ZONE = 1f / 3f

/**
 * How long a finger stays down before it is a hold, which pauses, rather
 * than a tap, which steps. A quarter of a second: past a deliberate tap,
 * well short of the platform's long press, so a hold pauses as soon as it
 * means to.
 */
internal const val HOLD_MS = 250L
