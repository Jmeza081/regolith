package com.regolith.domain.artwork

/**
 * The images the app keeps per thing (design section 08, "Where each image
 * lands"). A poster is 2:3 and a thumb is 16:9; both are generated from the
 * same source and cached side by side, so a screen never waits on a second
 * extraction to switch shapes.
 */
enum class ArtworkKind(val width: Int, val height: Int, val fileName: String) {
    /** Library tiles, Media grid, Title Detail. */
    POSTER(500, 750, "poster.jpg"),

    /** Resume row, Browse grid, search results, the player's scrub preview size. */
    THUMB(320, 180, "thumb.jpg"),

    ;

    companion object {
        /** Both kinds, which every frame grab, sidecar and mosaic writes together. */
        val stills = listOf(POSTER, THUMB)
    }
}

/** Where an image came from, in the order the resolver tries them. */
enum class ArtworkSource {
    /** poster/folder/cover/thumb.* beside a title, or at a collection root. */
    SIDECAR,

    /** An image sharing the video's basename: `Arrival.2016.mkv` + `Arrival.2016.jpg`. */
    BASENAME,

    /** Cover art inside the container (MP4 `covr`). */
    EMBEDDED,

    /** A frame grabbed from the middle of the runtime. */
    FRAMEGRAB,

    /** A 2x2 stitch of frames from the videos inside a folder, when it has no sidecar. */
    MOSAIC,

    /** Nothing readable: the tile carries the wedge and the filename. */
    PLACEHOLDER,
}

/** What an image belongs to. Ids are Room ids, stable across rescans (guardrail G3). */
sealed interface ArtworkOwner {
    /** Stable name used in the cache directory and the `artwork` table. */
    val typeName: String
    val id: Long

    data class File(override val id: Long) : ArtworkOwner {
        override val typeName get() = "file"
    }

    data class Folder(override val id: Long) : ArtworkOwner {
        override val typeName get() = "folder"
    }
}

/**
 * One image the UI wants. This is what a tile hands to Coil as its model;
 * the app's fetcher turns it into bytes from the artwork directory,
 * resolving on a miss (guardrail G5).
 */
data class ArtworkRequest(val owner: ArtworkOwner, val kind: ArtworkKind)
