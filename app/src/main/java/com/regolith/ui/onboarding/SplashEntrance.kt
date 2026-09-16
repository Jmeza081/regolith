package com.regolith.ui.onboarding

import kotlin.math.pow

/*
 * The splash's entrance, as arithmetic.
 *
 * Compose animations cannot be unit-tested without a device, so the timing
 * lives here as pure functions of elapsed milliseconds and the screen just
 * draws what they return. That also keeps one rule provable: the whole
 * entrance has to FINISH before the splash goes away ([SPLASH_MS]), or the
 * mark is still sliding when it fades out.
 */

/** How long the splash stays up on a cold start, then it fades. */
const val SPLASH_MS = 1_400L

/** Each band starts this much after the one above it. */
internal const val BAND_STAGGER_MS = 80

/** How long one band takes to travel home. */
internal const val BAND_DURATION_MS = 520

/**
 * How far out a band starts, in the wedge's viewport units.
 *
 * It MUST stay under the viewport's 100, or the mark is empty on the first
 * frame: bands are clipped to the wedge, so one pushed a full width away
 * has nothing inside the clip to draw, whatever its alpha. That left a
 * blank screen between the system splash going and the first band
 * arriving -- measured at 300-400 ms on the emulator. At 72 every band
 * still shows a sliver at rest, so the mark is never nothing.
 */
internal const val BAND_TRAVEL = 72f

/** The wordmark waits for the mark to be nearly whole. */
internal const val WORDMARK_DELAY_MS = 700

internal const val WORDMARK_DURATION_MS = 520

/** How far below its place the wordmark starts, in dp. */
internal const val WORDMARK_RISE_DP = 14f

/**
 * How solid a band is before it has travelled at all.
 *
 * Not zero, and this is the whole point: the system splash hands over a
 * wedge, and if our first frames are transparent the screen goes BLANK in
 * between. Measured on the emulator: 400 ms of nothing with a floor of 0,
 * against 100 ms for the old static splash. A ghost of the mark on the
 * first frame makes the hand-over continuous, and the bands still firm up
 * as they settle.
 */
internal const val BAND_ALPHA_FLOOR = 0.28f

/** The last thing to finish. Shorter than [SPLASH_MS], which the test pins. */
internal const val ENTRANCE_TOTAL_MS = WORDMARK_DELAY_MS + WORDMARK_DURATION_MS

/** Decelerating: fast away, gentle arrival. A cubic ease-out. */
internal fun easeOut(t: Float): Float = 1f - (1f - t.coerceIn(0f, 1f)).pow(3)

/** Eased 0..1 for a leg that waits [delayMs] then runs for [durationMs]. */
internal fun progressAt(elapsedMs: Long, delayMs: Int, durationMs: Int): Float =
    easeOut((elapsedMs - delayMs).toFloat() / durationMs)

/**
 * Where band [index] is at [elapsedMs], in viewport units. Bands come from
 * alternating sides so the mark knits itself together rather than sliding
 * in as one block.
 */
internal fun bandOffsetX(index: Int, elapsedMs: Long): Float =
    (1f - progressAt(elapsedMs, index * BAND_STAGGER_MS, BAND_DURATION_MS)) * bandTravel(index)

/** Even bands arrive from the left, odd ones from the right. */
internal fun bandTravel(index: Int): Float = if (index % 2 == 0) -BAND_TRAVEL else BAND_TRAVEL

/**
 * A band firms up as it travels, from [BAND_ALPHA_FLOOR] to solid, so
 * nothing pops in at the edge of the mark and nothing is invisible at rest.
 */
internal fun bandAlpha(index: Int, elapsedMs: Long): Float =
    BAND_ALPHA_FLOOR + (1f - BAND_ALPHA_FLOOR) * progressAt(elapsedMs, index * BAND_STAGGER_MS, BAND_DURATION_MS)

/** 0..1 for the wordmark, which fades in as it rises into place. */
internal fun wordmarkProgress(elapsedMs: Long): Float =
    progressAt(elapsedMs, WORDMARK_DELAY_MS, WORDMARK_DURATION_MS)
