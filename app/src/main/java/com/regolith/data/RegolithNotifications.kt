package com.regolith.data

/**
 * Every notification id and channel the app posts, in one place.
 *
 * Android identifies a notification by an integer, and posting a second one
 * with the same id REPLACES the first rather than adding to it. The scan,
 * the artwork walk and the downloads are all foreground WorkManager jobs
 * that can run at the same time, and the artwork walk and the downloads
 * both used to hard-code 42 in their own files: an artwork pass during a
 * download meant two jobs fighting over one row in the shade, each
 * overwriting whatever the other had just written.
 *
 * Keeping the ids together is the actual fix. Renumbering the collision
 * would have left the next worker free to pick 43.
 */
object RegolithNotifications {
    const val SCAN_ID = 41
    const val ARTWORK_ID = 42
    const val TRANSFERS_ID = 43

    const val CHANNEL_SCAN = "scan"
    const val CHANNEL_ARTWORK = "artwork"
    const val CHANNEL_TRANSFERS = "transfers"
}
