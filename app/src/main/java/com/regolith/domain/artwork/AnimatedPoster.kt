package com.regolith.domain.artwork

/**
 * A folder poster that moves: a GIF, played on the Library's first screen.
 *
 * Only a top-level folder's poster moves, which is a collection on that
 * screen: a handful of tiles at once. A whole wall of moving tiles would
 * cost battery and smoothness for little. Anywhere else a GIF is an
 * ordinary folder picture. Its first frame is cropped and stored like any
 * other ([ArtworkCandidates.imageExtensions]).
 *
 * Pure. `ArtworkStore` keeps the GIF as it came, beside the still poster
 * made from it, and Coil's animated decoder plays it.
 */
object AnimatedPoster {

    /**
     * What a moving poster is called: on the share when it is uploaded from
     * the phone, and in the artwork directory beside the still `poster.jpg`.
     */
    const val NAME = "poster.gif"

    const val MIME_TYPE = "image/gif"

    /** Whether the poster of the folder at [folderRelPath] (`""` for a share root) may move: a top-level folder only. */
    fun allowedFor(folderRelPath: String): Boolean = folderRelPath.isNotEmpty() && '/' !in folderRelPath

    /** True for GIF data, by its signature (`GIF87a`, `GIF89a`) rather than a file name, which can say anything. */
    fun isGif(bytes: ByteArray): Boolean {
        if (bytes.size < SIGNATURE_LENGTH) return false
        val head = String(bytes, 0, SIGNATURE_LENGTH, Charsets.US_ASCII)
        return head == "GIF87a" || head == "GIF89a"
    }

    /**
     * What becomes of a picture picked on the phone as the poster of the
     * folder at [folderRelPath], given its [mimeType] and [sizeBytes] (null
     * when the phone does not say).
     */
    fun forPicked(mimeType: String?, sizeBytes: Long?, folderRelPath: String): PickedPoster = when {
        mimeType != MIME_TYPE -> PickedPoster.STILL
        !allowedFor(folderRelPath) -> PickedPoster.STILL_NESTED
        // An unknown size is checked again as the picture is read.
        sizeBytes != null && sizeBytes > ArtworkCandidates.MAX_IMAGE_BYTES -> PickedPoster.STILL_TOO_BIG
        else -> PickedPoster.ANIMATED
    }

    private const val SIGNATURE_LENGTH = 6
}

/** How a picture picked as a folder poster goes up ([AnimatedPoster.forPicked]). */
enum class PickedPoster(val fileName: String) {
    /** Not a GIF: decoded and saved again as `poster.jpg`, as every poster is. */
    STILL(FolderPoster.NAME),

    /** A GIF for a top-level folder: sent unchanged as `poster.gif`, so it moves. */
    ANIMATED(AnimatedPoster.NAME),

    /**
     * A GIF over the 8 MB a folder picture may be
     * ([ArtworkCandidates.MAX_IMAGE_BYTES]). Its first frame becomes
     * `poster.jpg`: sent as it is, the app would skip it as too big.
     */
    STILL_TOO_BIG(FolderPoster.NAME),

    /** A GIF for a folder below the top level, where posters do not move. Its first frame becomes `poster.jpg`. */
    STILL_NESTED(FolderPoster.NAME),
}
