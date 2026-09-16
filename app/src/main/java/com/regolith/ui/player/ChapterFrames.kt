package com.regolith.ui.player

import com.regolith.domain.playback.FrameIndex

/*
 * When the chapter sheet is worth opening.
 *
 * The pill used to spin until the chapter LIST had settled and then open on
 * a sheet whose pictures were still arriving, so half the tiles were bare
 * skeletons that filled in one by one. The frames are what people are there
 * to look at, so the pill now waits for them too.
 *
 * "Waits for them" cannot mean "waits for all of them, however long that
 * takes". Three things make that a trap, and each one would hang the button
 * for good:
 *
 *  1. The frames live in an LRU of [FrameIndex.DEFAULT_CAPACITY]. A film
 *     with more chapters than that evicts its earliest frames as the last
 *     ones arrive, so "every tile has a picture" is never true at once.
 *  2. A bucket neither extractor can place is dropped for good
 *     (`OnDemandScrubThumbnails.giveUp`). Its frame is never coming.
 *  3. With thumbnails switched off there are no frames at all, by choice.
 *
 * So this is a settling rule rather than a completeness one, and every
 * branch of it ends with the sheet opening.
 */

/**
 * No new frame for this long means the worker has finished with what it can
 * get, so the rest are not coming.
 *
 * It has to sit comfortably ABOVE the gap between two frames, or a film that
 * is filling steadily is mistaken for a stalled one. Measured on the
 * emulator: when the platform retriever fails its fingerprint check the
 * media3 fallback takes ~1.2 s a frame, so 1.5 s was far too tight.
 */
internal const val FRAMES_STALL_MS = 2_500L

/**
 * Nothing at all after this long: the first frame is the slowest (the source
 * has to be opened first), but a share this quiet is not about to deliver.
 */
internal const val FRAMES_NO_FIRST_FRAME_MS = 8_000L

/**
 * The backstop, however busy it looks. Deliberately generous: waiting is the
 * POINT of this gate, and a film whose frames keep arriving should be allowed
 * to finish. Only a pathological case reaches this.
 */
internal const val FRAMES_CEILING_MS = 20_000L

/** How often the wait re-checks itself while it is running. */
internal const val FRAMES_POLL_MS = 200L

/**
 * Whether the sheet can open.
 *
 * @param chapterCount tiles the sheet will draw.
 * @param framesPresent how many of them already have a picture.
 * @param thumbnailsOff the setting is off, so no picture is ever coming.
 * @param msSinceRequest since the frames were asked for.
 * @param msSinceArrival since the last frame landed.
 */
internal fun chapterFramesSettled(
    chapterCount: Int,
    framesPresent: Int,
    thumbnailsOff: Boolean,
    msSinceRequest: Long,
    msSinceArrival: Long,
    capacity: Int = FrameIndex.DEFAULT_CAPACITY,
): Boolean = when {
    // Nothing to wait for.
    thumbnailsOff || chapterCount == 0 -> true
    // Every tile has its picture: the good case, and the common one.
    framesPresent >= chapterCount -> true
    // More tiles than the index can hold; they can never all be in at once.
    chapterCount > capacity -> true
    // Something arrived, then nothing for a while: the rest are not coming.
    // Measured against the GAP between frames, not the total, so a film that
    // is still filling in keeps the sheet shut until it has finished.
    framesPresent > 0 && msSinceArrival >= FRAMES_STALL_MS -> true
    // Nothing at all: the share is quiet or the source never opened.
    framesPresent == 0 && msSinceRequest >= FRAMES_NO_FIRST_FRAME_MS -> true
    // The backstop, so no film can leave a dead button for good.
    msSinceRequest >= FRAMES_CEILING_MS -> true
    else -> false
}
