package com.regolith.domain.library

import com.regolith.domain.transfer.UploadTally
import com.regolith.domain.transfer.UploadWording

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
 * @param paused nothing is moving (an upload waiting for its share): the bar
 *   holds where it stopped, greyed, instead of claiming progress.
 * @param target where tapping the tier goes, or null when it is not a way
 *   anywhere. Only uploads have one: they are going SOMEWHERE.
 */
data class BackgroundWork(
    val line: String,
    val fraction: Float?,
    val paused: Boolean = false,
    val target: TierTarget? = null,
)

/** Where a tap on the tier goes: a Browse folder, or Browse itself when [folderId] is null. */
data class TierTarget(val folderId: Long?)

/**
 * Uploads as the tier needs them: the numbers, and the two names it says.
 * [folderName] is null when the uploads go to more than one folder.
 */
data class UploadTier(val tally: UploadTally, val folderName: String?, val serverName: String?)

/** Shares being walked right now, and how many files they have turned up between them. */
data class ScanTally(val shares: Int, val files: Int)

/** The artwork walk: pictures made, out of pictures to make. */
data class ArtworkTally(val done: Int, val total: Int)

/**
 * The one background job worth reporting, or null when nothing is running.
 *
 * Uploads outrank everything: they are the one job the user started on
 * purpose and is watching, files leaving the phone that they want to see
 * arrive. A scan outranks an artwork pass because it is the one that
 * changes what is IN the library: while it runs, folders read "Not listed
 * yet" and films are still arriving on the wall, which is the thing a
 * person needs explaining. Artwork only changes how what is already there
 * looks.
 */
fun backgroundWork(scan: ScanTally?, artwork: ArtworkTally?, upload: UploadTier? = null): BackgroundWork? = when {
    upload != null -> BackgroundWork(
        line = UploadWording.tierLine(upload.tally, upload.serverName, upload.folderName),
        fraction = upload.tally.fraction ?: 0f,
        paused = upload.tally.paused,
        target = TierTarget(upload.tally.folderId),
    )
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
