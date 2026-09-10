package com.regolith.domain.artwork

/**
 * The images the app keeps per thing (design section 08, "Where each image
 * lands"). A poster is 2:3 and a thumb is 16:9; both are generated from the
 * same source and cached side by side, so a screen never waits on a second
 * extraction to switch shapes.
 *
 * [PREVIEW] is the odd one out and is NOT one of the [stills]: it is a
 * sprite sheet for Settings › Display › Moving tiles, generated separately
 * and only when that setting is on.
 */
enum class ArtworkKind(val width: Int, val height: Int, val fileName: String) {
    /** Library tiles, Media grid, Title Detail. */
    POSTER(500, 750, "poster.jpg"),

    /** Resume row, Browse grid, search results, the player's scrub preview size. */
    THUMB(320, 180, "thumb.jpg"),

    /**
     * A moving tile's frames, packed edge to edge into ONE image:
     * [PREVIEW_COLUMNS] × [PREVIEW_ROWS] cells of [PREVIEW_CELL_WIDTH] ×
     * [PREVIEW_CELL_HEIGHT]. A sprite sheet rather than an animated file
     * because Android can decode animated WebP and GIF but cannot ENCODE
     * either — there is no public API for it. One JPEG is also one Coil
     * request, one decode and one memory-cache entry per tile, where twelve
     * separate frames would be twelve of each.
     */
    PREVIEW(PREVIEW_CELL_WIDTH * PREVIEW_COLUMNS, PREVIEW_CELL_HEIGHT * PREVIEW_ROWS, "preview.jpg"),
    ;

    companion object {
        /** The two still kinds, which every frame grab and sidecar writes together. */
        val stills = listOf(POSTER, THUMB)
    }
}

/** A preview cell: small enough that a wall of decoded sheets stays affordable. */
const val PREVIEW_CELL_WIDTH = 240
const val PREVIEW_CELL_HEIGHT = 135
const val PREVIEW_COLUMNS = 4
const val PREVIEW_ROWS = 3

/** Twelve frames, played at [PREVIEW_FPS]: a three-second loop of the film. */
const val PREVIEW_FRAMES = PREVIEW_COLUMNS * PREVIEW_ROWS
const val PREVIEW_FPS = 4

/**
 * Where the frames are taken from: evenly spaced across the middle of the
 * runtime, avoiding the black open and the credits. Pure, so it is unit
 * testable without a file.
 */
fun previewPositionsMs(durationMs: Long): List<Long> {
    if (durationMs <= 0) return emptyList()
    val start = (durationMs * 0.10).toLong()
    val end = (durationMs * 0.80).toLong()
    val step = (end - start) / PREVIEW_FRAMES
    return List(PREVIEW_FRAMES) { start + step * it }
}

/** Where an image came from, in the order the resolver tries them. */
enum class ArtworkSource {
    /** poster/folder/cover/thumb.* beside a title, or at a collection root. */
    SIDECAR,

    /** An image sharing the video's basename: `Arrival.2016.mkv` + `Arrival.2016.jpg`. */
    BASENAME,

    /** Cover art inside the container (MP4 `covr`). */
    EMBEDDED,

    /** A frame grabbed at 10% of the runtime. */
    FRAMEGRAB,

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
