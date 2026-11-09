package com.regolith.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Whether something has been scrolled out of the way: the nav pill on a
 * phone, and the Library's chip row.
 *
 * Reading a wall of films is a downward motion, and on a phone the chrome
 * eats space a screen has little to spare (112dp for the pill, 46dp for the
 * chips). So: scrolling DOWN puts it away and KEEPS it away for as long as
 * you keep going, and scrolling back up brings it straight back. The pill
 * also comes back on a tap ([onPress], [onRelease]); the chips, which sit
 * above the wall, do not need that.
 *
 * For the pill this is a second, independent reason it can be absent. The
 * first is the three-second idle timer, which the nav graph already owns;
 * the pill shows only when neither reason applies. They do not fight,
 * because "hidden" always wins over "visible".
 *
 * ## Why a nested-scroll connection
 *
 * The six scrolling containers in the app are not the same kind of thing —
 * Home and Settings are a `Column` + `verticalScroll`, while Library, Browse
 * and Search are `LazyColumn`s and `LazyVerticalGrid`s — and none of them
 * hoists its scroll state. A nested-scroll connection sits ABOVE all of
 * them and sees every one, so this is six call sites left alone rather than
 * six screens taught about the nav.
 *
 * Web analogy: one scroll listener on the layout element, instead of a
 * `useScrollDirection()` hook wired into every page component.
 *
 * ## Why a threshold rather than a raw delta
 *
 * A finger is never still. Flipping on the first pixel of movement makes
 * the pill flicker on and off through a slow drag, so direction only counts
 * once it has been held for [SCROLL_AWAY_THRESHOLD]; reversing the drag
 * starts that tally again.
 */
@Stable
class ScrollAway(private val thresholdPx: Float) {

    /** True when a downward scroll has put it away. */
    var hidden by mutableStateOf(false)
        private set

    /**
     * Distance travelled since the last flip, in the direction being
     * travelled. Sign is Compose's: dragging the finger UP to read further
     * down a page is NEGATIVE.
     */
    private var travel = 0f

    /** Whether the gesture in progress has scrolled. Resets on each press. */
    private var scrolled = false

    /** Bring it back: a new screen, the top of a list, or the setting being switched off. */
    fun reveal() {
        hidden = false
        travel = 0f
    }

    /** A finger went down. Assume a tap until a scroll delta says otherwise. */
    fun onPress() {
        scrolled = false
    }

    /**
     * The last finger left. A gesture that never scrolled was a tap, and a
     * tap is the "touch anything else" case: the user is reaching for
     * something, so give them the nav back.
     *
     * A fling is not caught here, and should not be: the drag that threw it
     * already set [scrolled], so letting go mid-fling does not flash the
     * pill on top of content still moving underneath it.
     */
    fun onRelease() {
        if (!scrolled) reveal()
    }

    /**
     * One scroll delta, in Compose's sign convention: dragging the finger UP
     * to read further down a page is NEGATIVE.
     *
     * Split out from [connection] so the rule can be unit-tested as plain
     * arithmetic, with no composition and no Compose scroll types.
     */
    fun onScroll(dy: Float) {
        if (dy == 0f) return
        scrolled = true
        // The finger changed its mind: start the tally again rather than
        // spending the distance already banked the other way.
        if ((dy < 0f) != (travel < 0f)) travel = 0f
        travel += dy
        when {
            travel <= -thresholdPx -> {
                hidden = true
                travel = 0f
            }
            travel >= thresholdPx -> {
                hidden = false
                travel = 0f
            }
        }
    }

    val connection = object : NestedScrollConnection {
        // onPreScroll, not onPostScroll: the direction of a gesture is worth
        // knowing even when a list already at the end of its range consumes
        // nothing, and this reports it either way. Nothing is consumed here —
        // the pill only watches.
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            onScroll(available.y)
            return Offset.Zero
        }
    }
}

/** How far one direction must be held before anything moves. Roughly a thumb's twitch. */
private val SCROLL_AWAY_THRESHOLD: Dp = 24.dp

/** A [ScrollAway] for this composition, with the shared threshold. */
@Composable
fun rememberScrollAway(): ScrollAway {
    val density = LocalDensity.current
    return remember(density) { ScrollAway(with(density) { SCROLL_AWAY_THRESHOLD.toPx() }) }
}
