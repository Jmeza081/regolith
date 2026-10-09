package com.regolith.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/**
 * The nudge a jump from an [AlphabetRail] plays on what it lands on: out
 * toward the rail and back with a bounce, so the eye finds the row or tile
 * among its neighbours. The move sheet's folders and the Library's walls
 * play the same one.
 */
@Stable
class RailNudge internal constructor(
    internal val shove: Animatable<Float, AnimationVector1D>,
    /** 1 toward the end edge, where the rail is: -1 in right-to-left. */
    internal val towardRail: Float,
)

/**
 * A nudge for one row or tile: [count] above zero plays it, and each new
 * value plays it again; zero settles it at rest.
 */
@Composable
fun rememberRailNudge(count: Int): RailNudge {
    val shove = remember { Animatable(0f) }
    val towardRail = if (LocalLayoutDirection.current == LayoutDirection.Rtl) -1f else 1f
    LaunchedEffect(count) {
        if (count == 0) {
            shove.animateTo(0f)
        } else {
            shove.animateTo(1f, tween(NUDGE_OUT_MS, easing = FastOutSlowInEasing))
            shove.animateTo(0f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow))
        }
    }
    return remember(shove, towardRail) { RailNudge(shove, towardRail) }
}

/**
 * Moves this by [nudge], read in the draw phase so the nudge recomposes
 * nothing, frame after frame. [distance] is how far it travels: a row's
 * default, or less for a tile in a grid, which must stop short of the tile
 * beside it.
 */
fun Modifier.railNudge(nudge: RailNudge?, distance: Dp = NudgeDistance): Modifier =
    if (nudge == null) this else graphicsLayer { translationX = nudge.shove.value * nudge.towardRail * distance.toPx() }

/**
 * Long enough for the spring to settle; after that a nudge is history. A
 * list forgets which row it nudged by then, so a row scrolled back into
 * view does not play it again.
 */
const val RAIL_NUDGE_FORGET_MS = 900L

/** How far a nudged row travels before it springs back. */
private val NudgeDistance = 12.dp

private const val NUDGE_OUT_MS = 110
