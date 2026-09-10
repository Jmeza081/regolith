package com.regolith.ui.navigation

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.EaseOutCubic
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
