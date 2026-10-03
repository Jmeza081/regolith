package com.regolith.ui.navigation

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.navigation3.ui.NavDisplay

/*
 * How screens replace each other.
 *
 * Navigation 3's default is a scale-and-fade: the screen you leave shrinks
 * away from you. That reads as "this thing was destroyed" rather than
 * "you moved", which is why it looks wrong on the way out of a screen.
 *
 * Instead: a push slides the new screen in from the right while the old
 * one drifts a sixth of the way left and fades — the parallax says the old
 * screen is still there, just behind. A pop plays it backwards. Nothing
 * scales, so nothing appears to fly toward or away from the viewer.
 *
 * Tabs are the exception. The four pill destinations are siblings, not a
 * stack, so switching between them cross-fades: horizontal motion there
 * would imply an order the pill does not have.
 *
 * Web analogy: a router transition group, with a different pair of
 * enter/exit classes for "navigate deeper" and "switch tab".
 */

private const val PUSH_MS = 220
private const val POP_MS = 180
private const val TAB_MS = 140

/** How far the outgoing screen drifts: a sixth of the width, the usual parallax ratio. */
private const val PARALLAX = 6

private val pushTransition: ContentTransform
    get() = (slideInHorizontally(tween(PUSH_MS, easing = EaseOutCubic)) { width -> width } + fadeIn(tween(PUSH_MS))) togetherWith
        (slideOutHorizontally(tween(PUSH_MS, easing = EaseOutCubic)) { width -> -width / PARALLAX } + fadeOut(tween(PUSH_MS)))

/**
 * The reverse. Also used for predictive back, where the system drives this
 * same transform from the drag rather than from a clock.
 */
private val popTransition: ContentTransform
    get() = (slideInHorizontally(tween(POP_MS, easing = EaseOutCubic)) { width -> -width / PARALLAX } + fadeIn(tween(POP_MS))) togetherWith
        (slideOutHorizontally(tween(POP_MS, easing = EaseOutCubic)) { width -> width } + fadeOut(tween(POP_MS)))

private val tabTransition: ContentTransform
    get() = fadeIn(tween(TAB_MS)) togetherWith fadeOut(tween(TAB_MS))

/** Attached to [NavDisplay] as the default for every pushed screen. */
val pushSpec: (Any) -> ContentTransform = { pushTransition }

val popSpec: (Any) -> ContentTransform = { popTransition }

/**
 * Per-entry metadata for the four tab destinations, so they cross-fade
 * while everything else slides. Spread onto `entry<Key>(metadata = tabScreen)`.
 */
val tabScreen: Map<String, Any> =
    NavDisplay.transitionSpec { tabTransition } +
        NavDisplay.popTransitionSpec { tabTransition } +
        NavDisplay.predictivePopTransitionSpec { _ -> tabTransition }

/*
 * A title's page opening beside a wall on a wide window (WallScene).
 *
 * The page alone moves: in from the end edge on the push's clock, back out
 * on the pop's. The wall beside it never slides — it is the thing you were
 * looking at, and it fades through to its new width instead (below). So the scene
 * as a whole does nothing ([paneScene]) and the page carries its own motion
 * through `animateEnterExit`, which NavDisplay's transition drives — and
 * predictive back seeks, so the page follows the thumb out.
 */

/** The page arriving. */
val paneEnter: EnterTransition
    get() = slideInHorizontally(tween(PUSH_MS, easing = EaseOutCubic)) { width -> width }

/** The page leaving: ✕, back, or its tile tapped again. */
val paneExit: ExitTransition
    get() = slideOutHorizontally(tween(POP_MS, easing = EaseOutCubic)) { width -> width }

/*
 * The wall changing width for a page that comes or goes (WallScene). Two
 * columns beside a page and five without is too big a re-arrangement to
 * animate tile by tile: gliding there, the tiles crossed over each other the
 * whole way. So it is a "fade through" (Material's pattern for one layout
 * replacing another): the wall fades out as it was, changes width while it
 * cannot be seen, and fades in at the new one. The page slides meanwhile.
 */

/** How long a wall's fade through takes, from the moment the page starts to move. */
const val WALL_FADE_THROUGH_MS = 240

/** How far into the fade through the wall is gone and changes width: Material's 30%. */
const val WALL_FADE_THROUGH_SWITCH = 0.3f

/** The wall's alpha [progress] of the way through its fade through: out, then in. */
fun wallFadeThroughAlpha(progress: Float): Float = when {
    progress >= 1f -> 1f
    progress < WALL_FADE_THROUGH_SWITCH -> 1f - FastOutLinearInEasing.transform(progress / WALL_FADE_THROUGH_SWITCH)
    else -> LinearOutSlowInEasing.transform((progress - WALL_FADE_THROUGH_SWITCH) / (1f - WALL_FADE_THROUGH_SWITCH))
}

// ExitTransition.None still keeps the outgoing scene composed until every
// animation inside it has finished — the page's slide-out among them.
private val paneSceneTransition: ContentTransform
    get() = EnterTransition.None togetherWith ExitTransition.None

/** Scene metadata for a wall with a page beside it: no scene-wide motion, in either direction. */
val paneScene: Map<String, Any> =
    NavDisplay.transitionSpec { paneSceneTransition } +
        NavDisplay.popTransitionSpec { paneSceneTransition } +
        NavDisplay.predictivePopTransitionSpec { _ -> paneSceneTransition }
