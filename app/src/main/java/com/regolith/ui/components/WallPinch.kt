package com.regolith.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.regolith.R
import com.regolith.domain.display.PostersPerRow
import com.regolith.domain.display.pinchStep
import com.regolith.ui.theme.PillShape
import com.regolith.ui.theme.RegolithTheme
import com.regolith.ui.theme.Spacing
import com.regolith.ui.theme.TextStyles
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Pinching a poster wall to change how many posters sit across it, one step
 * at a time: spread two fingers and the posters get bigger (fewer across),
 * pinch them together and they get smaller (more). Each step is a tick you
 * feel and the "N across" pill at the top of the wall ([WallPinchPill]); past
 * either end there is a bump instead, and the pill shakes. The owner chose
 * steps over a live zoom that follows the fingers: "that's the way I
 * anticipated it".
 *
 * Every poster wall on the inner display uses it — the Library's, its On
 * this device grid, and Home's — and each step goes to [rememberWallPinch]'s
 * `onStep`, which keeps it as Settings › Display › Posters per row: one
 * setting, whichever wall was pinched.
 *
 * Web analogy: a gesture handler with a tiny store of its own for the toast
 * it shows, the way a `usePinch()` hook might keep `{ active, count }`.
 */
@Stable
class WallPinch internal constructor(
    private val onStep: State<(Int) -> Unit>,
    private val haptics: HapticFeedback,
    private val scope: CoroutineScope,
) {
    /**
     * The posters across the wall shows, and the most that fit. Kept from
     * the caller's value except during a pinch, when the steps run ahead of
     * the setting they write (that write is asynchronous, and a step taken
     * from a stale count would land twice).
     */
    internal var shown by mutableIntStateOf(0)
    internal var most by mutableIntStateOf(0)

    /** The count the pill shows; null while it is out of sight. */
    var pill: Int? by mutableStateOf(null)
        private set

    /** Counts the bumps at either end; the pill shakes on each new one. */
    var bumps by mutableIntStateOf(0)
        private set

    /** True while two fingers are on the wall. */
    var active by mutableStateOf(false)
        private set

    /**
     * On a lazy wall, the poster the pinch began on, until the pill has gone.
     * Taken the moment the second finger lands: by the time an effect could
     * look, a quick pinch may already have stepped and the grid moved under
     * the fingers.
     */
    internal var anchor: PinchAnchor? = null
        private set

    private var hide: Job? = null

    internal fun begin(at: Offset, grid: LazyGridState?) {
        hide?.cancel()
        active = true
        anchor = grid?.anchorAt(at)
        pill = shown
    }

    /** One step's worth of pinch: [fewer] when the fingers spread. */
    internal fun step(fewer: Boolean) {
        val next = pinchStep(shown, fewer, most)
        if (next == null) {
            bumps++
            haptics.performHapticFeedback(HapticFeedbackType.Reject)
            return
        }
        shown = next
        pill = next
        haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
        onStep.value(next)
    }

    internal fun end() {
        active = false
        hide?.cancel()
        hide = scope.launch {
            delay(PILL_LINGER_MS)
            pill = null
            anchor = null
        }
    }
}

/** A poster on a lazy wall, by [index], and where its [top] was in [grid]'s viewport. */
internal class PinchAnchor(val grid: LazyGridState, val index: Int, val top: Int)

/**
 * A [WallPinch] for a wall that shows [shown] posters across, where [most]
 * fit at all. [onStep] gets each new count, to keep as the setting.
 */
@Composable
fun rememberWallPinch(shown: Int, most: Int, onStep: (Int) -> Unit): WallPinch {
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val step = rememberUpdatedState(onStep)
    val pinch = remember(haptics, scope) { WallPinch(step, haptics, scope).also { it.shown = shown; it.most = most } }
    SideEffect {
        pinch.most = most
        if (!pinch.active) pinch.shown = shown
    }
    // The wall's columns changed under a pinch: the poster it began on goes
    // back where it was (see [wallPinch]'s grid).
    LaunchedEffect(pinch, shown) {
        val anchor = pinch.anchor ?: return@LaunchedEffect
        anchor.grid.scrollToItem(anchor.index)
        anchor.grid.scrollBy(-anchor.top.toFloat())
    }
    return pinch
}

/**
 * The pinch itself, on a wall's container. Null leaves the wall alone: a
 * phone, a list in rows, or a page open beside the wall.
 *
 * One finger is never touched, so scrolling, taps and holds go on as before.
 * From the moment a second finger lands the gesture is the pinch's: the wall
 * stops scrolling and a tile stops counting toward a hold. That is why it
 * listens in the Initial pass, which reaches this modifier before the grid's
 * own scrolling and its tiles (the pointer events' capture phase, in DOM
 * terms). It keeps the gesture until the last finger is up, so lifting one
 * finger first can't turn the rest of a pinch into a scroll or a tap.
 *
 * A wall that stops being pinchable mid-pinch (the phone folds shut, a page
 * opens beside it) drops this modifier, and Compose reports the fingers as
 * lifted as it goes, so the pinch ends and its pill leaves as usual.
 *
 * On a lazy wall, pass its [grid] (and put this on the grid itself, so the
 * fingers and the tiles share coordinates): the poster the pinch began on
 * then stays where it was as the columns change. Left alone, a keyed grid
 * keeps its FIRST visible tile in place instead, and four across instead of
 * seven could leave the one you pinched a screen down. A wall that isn't
 * lazy (Home's) keeps its place by itself.
 */
fun Modifier.wallPinch(pinch: WallPinch?, grid: LazyGridState? = null): Modifier = if (pinch == null) this else pointerInput(pinch, grid) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        var pinching = false
        // Finger distance at the pinch's start or at its last step; a step
        // is a fifth again further apart or closer than that.
        var baseline = 0f
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val pressed = event.changes.filter { it.pressed }
            if (pressed.isEmpty()) break
            if (pressed.size >= 2) {
                val a = pressed[0].position
                val b = pressed[1].position
                val distance = (a - b).getDistance()
                if (!pinching) {
                    pinching = true
                    baseline = distance
                    pinch.begin((a + b) / 2f, grid)
                } else if (baseline > 0f) {
                    val ratio = distance / baseline
                    if (ratio >= STEP_RATIO || ratio <= 1f / STEP_RATIO) {
                        pinch.step(fewer = ratio > 1f)
                        baseline = distance
                    }
                }
            }
            if (pinching) event.changes.forEach { it.consume() }
        }
        if (pinching) pinch.end()
    }
}

/**
 * The grid item under [at] (the pinch's starting point, in the grid's own
 * coordinates), or the nearest one in the gaps between.
 */
private fun LazyGridState.anchorAt(at: Offset): PinchAnchor? {
    val items = layoutInfo.visibleItemsInfo.ifEmpty { return null }
    val under = items.firstOrNull {
        at.x >= it.offset.x && at.x < it.offset.x + it.size.width && at.y >= it.offset.y && at.y < it.offset.y + it.size.height
    } ?: items.minBy { abs(it.offset.x + it.size.width / 2f - at.x) + abs(it.offset.y + it.size.height / 2f - at.y) }
    return PinchAnchor(this, under.index, under.offset.y)
}

/**
 * The "N across" pill: how many posters across the wall shows, while a pinch
 * changes it and for a moment after. Beside the number, one mark for each
 * count the pinch can reach, the current one lit, so the pill also says how
 * far there is to go. It shakes when a pinch runs past an end, and TalkBack
 * reads each new count. Place it centred at the top of the wall.
 */
@Composable
fun WallPinchPill(pinch: WallPinch, modifier: Modifier = Modifier) {
    val colors = RegolithTheme.colors
    val shake = remember { Animatable(0f) }
    LaunchedEffect(pinch.bumps) {
        if (pinch.bumps == 0) return@LaunchedEffect
        shake.snapTo(0f)
        shake.animateTo(
            0f,
            keyframes {
                durationMillis = SHAKE_MS
                6f at 50
                -6f at 120
                3f at 190
            },
        )
    }
    // Held through the fade-out, so the number doesn't blank as it leaves.
    val last = remember { mutableIntStateOf(pinch.shown) }
    val live = pinch.pill
    SideEffect { if (live != null) last.intValue = live }
    val count = live ?: last.intValue
    AnimatedVisibility(
        visible = live != null,
        enter = fadeIn(tween(PILL_FADE_IN_MS)),
        exit = fadeOut(tween(PILL_FADE_OUT_MS)),
        modifier = modifier,
    ) {
        Row(
            Modifier
                .graphicsLayer { translationX = shake.value.dp.toPx() }
                .clip(PillShape)
                .background(colors.overArt)
                .border(1.dp, colors.onMediaBorder, PillShape)
                .padding(horizontal = Spacing.s18, vertical = 10.dp)
                .semantics { liveRegion = LiveRegionMode.Polite }
                .testTag("wall_pinch_pill"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.s12),
        ) {
            Icon(painterResource(R.drawable.rg_ic_library), contentDescription = null, tint = colors.ink, modifier = Modifier.size(16.dp))
            Text("$count across", style = TextStyles.message, color = colors.ink)
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s4)) {
                for (n in PostersPerRow.FOUR.count..minOf(PostersPerRow.SEVEN.count, pinch.most)) {
                    Box(
                        Modifier
                            .size(width = 16.dp, height = 4.dp)
                            .background(if (n == count) colors.ink else colors.onMediaBorder, PillShape),
                    )
                }
            }
        }
    }
}

/**
 * How much further apart (or closer) the fingers go for one step. A fifth:
 * a comfortable spread steps through all four counts, and a hand resting
 * on the glass doesn't step by accident.
 */
private const val STEP_RATIO = 1.2f

/** How long the pill stays after the fingers lift, long enough to read the count. */
private const val PILL_LINGER_MS = 900L

private const val PILL_FADE_IN_MS = 120
private const val PILL_FADE_OUT_MS = 220
private const val SHAKE_MS = 280
