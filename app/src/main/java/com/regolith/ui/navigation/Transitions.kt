package com.regolith.ui.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.navigation3.scene.Scene
import androidx.navigation3.ui.NavDisplay
import com.regolith.ui.components.FLIGHT_MS
import com.regolith.ui.components.FlightEasing

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
 * A page a poster flies into (PosterFlight.kt): Title Detail on a phone, or
 * filling a wide window. It fades in under the poster, rising a little,
 * instead of sliding in from the right — a slide would carry the poster's
 * landing spot sideways while the poster is still in the air. The screen it
 * covers fades as the poster leaves it. Back is the same in reverse, with the
 * poster flying home over it, and the Back swipe scrubs it.
 *
 * Every Title Detail arrives this way, flown into or not: a page opened from
 * a list row has no poster to bring, and fades in all the same.
 */

/** How far the page rises as it fades in: a sixtieth of its height, about 12dp on a phone, as drawn. */
private const val PAGE_RISE = 60

private val pageArrive: ContentTransform
    get() = (fadeIn(tween(FLIGHT_MS)) + slideInVertically(tween(FLIGHT_MS, easing = FlightEasing)) { height -> height / PAGE_RISE }) togetherWith
        fadeOut(tween(FLIGHT_MS / 2))

private val pageLeave: ContentTransform
    get() = fadeIn(tween(FLIGHT_MS)) togetherWith
        (fadeOut(tween(FLIGHT_MS / 2)) + slideOutVertically(tween(FLIGHT_MS, easing = FlightEasing)) { height -> height / PAGE_RISE })

/** Per-entry metadata for a page posters fly into. Spread onto `entry<Key>(metadata = pageScreen)`. */
val pageScreen: Map<String, Any> =
    NavDisplay.transitionSpec { pageArrive } +
        NavDisplay.popTransitionSpec { pageLeave } +
        NavDisplay.predictivePopTransitionSpec { _ -> pageLeave }

/*
 * The lightbox (LightboxScreen), which a picture flies into from its tile
 * and back out of: its black fades in over the screen it was opened from,
 * which is held drawn underneath for as long as that takes, and fades away
 * again on the way back, staying on top of the screen it reveals. A story
 * (StoryScreen) comes and goes the same way.
 */

private val lightboxArrive: ContentTransform
    get() = ContentTransform(fadeIn(tween(FLIGHT_MS)), fadeOut(tween(FLIGHT_MS), targetAlpha = 0.99f))

private val lightboxLeave: ContentTransform
    get() = ContentTransform(EnterTransition.None, fadeOut(tween(FLIGHT_MS)), targetContentZIndex = -1f)

/** Per-entry metadata for the lightbox and a story. */
val lightboxScreen: Map<String, Any> =
    NavDisplay.transitionSpec { lightboxArrive } +
        NavDisplay.popTransitionSpec { lightboxLeave } +
        NavDisplay.predictivePopTransitionSpec { _ -> lightboxLeave }

/*
 * The player (PlayerScreen), which moves itself on the transition NavDisplay
 * runs: put away, its picture shrinks into the mini player's spot; opened
 * from the mini player, it grows out of it; any other way in or out, it
 * slides. So the scene does nothing but keep both screens on hand until the
 * player has finished moving, the page underneath staying put — and when the
 * player is put away it stays drawn on top of that page as it shrinks
 * (targetContentZIndex), where a pop would normally put the page on top.
 */

/**
 * The page under the player kept on screen while the player grows over it.
 * AnimatedContent drops outgoing content the moment it has nothing left to
 * animate, so it is given a fade too slight to see that lasts as long as the
 * player's own motion. (Compose's own "keep until finished" is internal.)
 */
private val holdUnderPlayer: ExitTransition
    get() = fadeOut(tween(PLAYER_MOTION_MS), targetAlpha = 0.99f)

private val playerArrive: ContentTransform
    get() = ContentTransform(EnterTransition.None, holdUnderPlayer)

/** Putting it away: the player animates itself, which keeps it on screen; the page below is simply there. */
private val playerLeave: ContentTransform
    get() = ContentTransform(EnterTransition.None, ExitTransition.None, targetContentZIndex = -1f)

/** How long the player takes to shrink, grow or slide; PlayerScreen times its own motion by it. */
const val PLAYER_MOTION_MS = 360

/** Per-entry metadata for the player, which animates itself. */
val playerScreen: Map<String, Any> =
    NavDisplay.transitionSpec { playerArrive } +
        NavDisplay.popTransitionSpec { playerLeave } +
        NavDisplay.predictivePopTransitionSpec { _ -> playerLeave }

/*
 * A title's page opening beside a wall on a wide window (WallScene).
 *
 * The page alone moves: in from the end edge on the push's clock, back out
 * on the pop's. Opened by a tap on a tile it fades in instead ([paneFadeIn]),
 * under the poster flying into it. The wall beside it never slides — it is the thing you were
 * looking at, and it dissolves and reappears at its new width instead (below). So the scene
 * as a whole does nothing ([paneScene]) and the page carries its own motion
 * through `animateEnterExit`, which NavDisplay's transition drives. Back is
 * not predictive here: the scene answers it with the close button's own pop
 * (WallScene.WallWithPageLayout), so every way of closing the page looks
 * the same.
 */

/** The page arriving. */
val paneEnter: EnterTransition
    get() = slideInHorizontally(tween(PUSH_MS, easing = EaseOutCubic)) { width -> width }

/**
 * The page arriving under a poster flown in from the wall (PosterFlight.kt):
 * it fades in where it will stay, as on a phone, because a slide would carry
 * the poster's landing spot sideways while it is still in the air. It leaves
 * by [paneExit] all the same.
 */
val paneFadeIn: EnterTransition
    get() = fadeIn(tween(FLIGHT_MS))

/** The page leaving: its close button, back, or its tile tapped again. */
val paneExit: ExitTransition
    get() = slideOutHorizontally(tween(POP_MS, easing = EaseOutCubic)) { width -> width }

/*
 * The wall changing width for a page that comes or goes (WallScene.WallAt).
 * Two columns beside a page and five without is too big a re-arrangement to
 * animate tile by tile: gliding there, the tiles crossed over each other the
 * whole way. So the wall leaves as it was, changes width while it cannot be
 * seen, and arrives at the new one once its pictures are drawn. The page
 * slides meanwhile.
 */

/** How long the wall takes to dissolve as it was: inside the page's own slide, so the two leave together. */
const val WALL_LEAVE_MS = 120

/** How long the wall takes to fade in at its new width, once it is ready to be seen. */
const val WALL_ARRIVE_MS = 200

/**
 * The longest the wall waits at its new width for its pictures before it
 * fades in anyway: posters already on the phone decode in a frame or two,
 * and one still on its way from the share should not hold the wall back.
 */
const val WALL_PICTURES_WAIT_MS = 300L

/**
 * A page coming or going beside the same wall moves nothing scene-wide.
 * ExitTransition.None still keeps the outgoing scene composed until every
 * animation inside it has finished — the page's slide-out among them.
 *
 * Anything else — the wall's own back arrow takes the page AND the
 * collection off, landing on another wall or Home — cross-fades like a tab,
 * as leaving a collection without a page does. Kept still, the old wall half
 * stood fully drawn over the new screen until the page had slid out.
 */
private val AnimatedContentTransitionScope<Scene<*>>.paneSceneTransition: ContentTransform
    get() = if (initialState.isSameWallAs(targetState)) EnterTransition.None togetherWith ExitTransition.None else tabTransition

/** Scene metadata for a wall with a page beside it: still beside its own wall, a cross-fade anywhere else. */
val paneScene: Map<String, Any> =
    NavDisplay.transitionSpec { paneSceneTransition } +
        NavDisplay.popTransitionSpec { paneSceneTransition } +
        NavDisplay.predictivePopTransitionSpec { _ -> paneSceneTransition }
