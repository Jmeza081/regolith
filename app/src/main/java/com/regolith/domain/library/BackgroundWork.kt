package com.regolith.domain.library

/**
 * What the app is doing in the background, reduced to the one line and one
 * bar the nav chrome has room for.
 *
 * Pure so it can be unit-tested and so the wording lives in one place:
 * before this, Home said "Reading the share · 1,204 files so far", Library
 * said "Still reading the share · more will arrive" and Settings drew a bar
 * with no words at all — three screens describing one job three ways, and
 * none of them visible from the other two.
 *
 * @param line what is happening, already counted: "Reading 2 shares · 1,204 files".
 * @param fraction how far along, or null when the total is unknown and the
 *   bar has to sweep instead ([com.regolith.ui.components.SweepBar]).
 */
data class BackgroundWork(val line: String, val fraction: Float?)

/** Shares being walked right now, and how many files they have turned up between them. */
data class ScanTally(val shares: Int, val files: Int)

/** The artwork walk: pictures made, out of pictures to make. */
data class ArtworkTally(val done: Int, val total: Int)

/**
 * The one background job worth reporting, or null when nothing is running.
 *
 * A scan outranks an artwork pass because it is the one that changes what
 * is IN the library: while it runs, folders read "Not listed yet" and films
 * are still arriving on the wall, which is the thing a person needs
 * explaining. Artwork only changes how what is already there looks.
 */
fun backgroundWork(scan: ScanTally?, artwork: ArtworkTally?): BackgroundWork? = when {
    scan != null && scan.shares > 0 -> BackgroundWork(
        line = buildString {
            append(if (scan.shares == 1) "Reading the share" else "Reading ${scan.shares} shares")
            // Zero is not worth printing: a walk that has just started has
            // found nothing yet, and "· 0 files" reads like a failure.
            if (scan.files > 0) append(" · ${"%,d".format(scan.files)} files")
        },
        // A walk learns the shape of a share by walking it, so there is no
        // total to count towards until there is nothing left to do.
        fraction = null,
    )
    artwork != null && artwork.total > 0 -> BackgroundWork(
        line = "Preparing artwork · ${"%,d".format(artwork.done)} of ${"%,d".format(artwork.total)}",
        fraction = (artwork.done.toFloat() / artwork.total).coerceIn(0f, 1f),
    )
    // Running, but it has not worked out how much there is to do yet.
    artwork != null -> BackgroundWork(line = "Preparing artwork", fraction = null)
    else -> null
}
