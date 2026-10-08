package com.regolith.domain.media

/**
 * What Save to phone says when it is done (P20): pictures from the share
 * copied into the phone's gallery, in Pictures › Regolith. Pure, so the
 * wording is tested rather than read off a capsule.
 */
object PictureSaves {

    /** The gallery folder they go in, under Pictures; and how the line names it. */
    const val ALBUM = "Regolith"
    const val PLACE = "Pictures › $ALBUM"

    /** The line for a batch: [saved] of [total] made it. Null when there was nothing to save. */
    fun message(saved: Int, total: Int): Message? = when {
        total <= 0 -> null
        saved == total && total == 1 -> Message("Saved to your gallery, in $PLACE")
        saved == total -> Message("Saved $total pictures to your gallery, in $PLACE")
        saved == 0 && total == 1 -> Message("Couldn’t save the picture: the share didn’t hand it over", failed = true)
        saved == 0 -> Message("Couldn’t save the pictures: the share didn’t hand them over", failed = true)
        else -> Message("Saved $saved of $total pictures to $PLACE · the rest couldn’t be fetched", failed = true)
    }

    data class Message(val text: String, val failed: Boolean = false)
}
