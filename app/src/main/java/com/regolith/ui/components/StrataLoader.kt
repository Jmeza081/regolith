package com.regolith.ui.components

import android.provider.Settings
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.pow

/*
 * The Strata Wedge as a loading indicator: the splash's entrance, on repeat.
 *
 * Bands arrive from alternating sides, hold together for a beat, then fall
 * back out the way they came -- so the loop has no seam and the app's own
 * mark does the waiting instead of a circle.
 *
 * It replaces a spinner only where there is room for five bands and the gaps
 * between them. On the small ones (a 13dp pill, a 14dp button) the bands
 * would be under a pixel apart, so those keep their circles; that is a size
 * rule, not a taste one. It also leaves the LAN radar alone, whose rings
 * mean "sweeping a network" -- a meaning the wedge does not carry.
 */

/** One full arrive-hold-leave cycle. */
internal const val LOOP_CYCLE_MS = 2_400

/** Each band starts its cycle this much after the one above it, as on the splash. */
internal const val LOOP_STAGGER_MS = 80

/** How far out a band travels, in the wedge's 100-wide viewport. */
internal const val LOOP_TRAVEL = 52f

/** How solid a band is at the far end of its travel. Never zero: see [BAND_ALPHA_FLOOR]'s reasoning on the splash. */
internal const val LOOP_ALPHA_FLOOR = 0.28f

/** Fraction of the cycle spent arriving, then holding; the rest is the way out. */
private const val ARRIVE_END = 0.40f
private const val HOLD_END = 0.62f

/** Even bands come from the left, odd from the right, as on the splash. */
internal fun loopBandTravel(index: Int): Float = if (index % 2 == 0) -LOOP_TRAVEL else LOOP_TRAVEL

/** Where band [index] sits in its cycle at [elapsedMs], 0..1, wrapping. */
internal fun loopPhase(index: Int, elapsedMs: Long): Float =
    Math.floorMod(elapsedMs - index.toLong() * LOOP_STAGGER_MS, LOOP_CYCLE_MS.toLong()) / LOOP_CYCLE_MS.toFloat()

private fun easeOut(t: Float): Float = 1f - (1f - t.coerceIn(0f, 1f)).pow(3)

/** Displacement of band [index], in viewport units: out, home, back out. */
internal fun loopBandOffsetX(index: Int, elapsedMs: Long): Float {
    val p = loopPhase(index, elapsedMs)
    val travel = loopBandTravel(index)
    return when {
        p < ARRIVE_END -> (1f - easeOut(p / ARRIVE_END)) * travel
        p < HOLD_END -> 0f
        else -> easeOut((p - HOLD_END) / (1f - HOLD_END)) * travel
    }
}

/** Band [index]'s opacity through the same cycle; it firms up as it arrives. */
internal fun loopBandAlpha(index: Int, elapsedMs: Long): Float {
    val p = loopPhase(index, elapsedMs)
    val solid = 1f - LOOP_ALPHA_FLOOR
    return when {
        p < ARRIVE_END -> LOOP_ALPHA_FLOOR + solid * easeOut(p / ARRIVE_END)
        p < HOLD_END -> 1f
        else -> 1f - solid * easeOut((p - HOLD_END) / (1f - HOLD_END))
    }
}

/** The mark's own proportions, so a caller sizes it by height alone. */
private const val WEDGE_ASPECT = 58f / 78f

/**
 * A loading indicator that is the app's mark rather than a circle.
 *
 * @param height how tall the wedge stands; the width follows at 58:78.
 * @param testTag the caller's tag. It goes on the wrapper, NOT on the wedge:
 *   [StrataWedge] sets its own tag after the modifier it is handed, and the
 *   later `testTag` wins, so a tag passed straight through would vanish.
 */
@Composable
fun StrataLoader(
    modifier: Modifier = Modifier,
    height: Dp = 48.dp,
    tint: Color = Color.White,
    testTag: String? = null,
) {
    val context = LocalContext.current
    // Android has no "prefers-reduced-motion"; turning animations off shows up
    // as a zero duration scale. Then the mark simply stands there, whole --
    // not a frozen frame of the loop, which would be a wedge in pieces.
    val animated = remember(context) {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f
    }
    val transition = rememberInfiniteTransition(label = "strata_loader")
    val elapsed by transition.animateFloat(
        initialValue = 0f,
        targetValue = LOOP_CYCLE_MS.toFloat(),
        animationSpec = infiniteRepeatable(tween(LOOP_CYCLE_MS, easing = LinearEasing)),
        label = "elapsed",
    )
    val now = if (animated) elapsed.toLong() else 0L
    Box(
        modifier
            .semantics { contentDescription = "Loading" }
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        StrataWedge(
            width = height * WEDGE_ASPECT,
            height = height,
            tint = tint,
            bandOffsetX = { i -> if (animated) loopBandOffsetX(i, now) else 0f },
            bandAlpha = { i -> if (animated) loopBandAlpha(i, now) else 1f },
        )
    }
}
