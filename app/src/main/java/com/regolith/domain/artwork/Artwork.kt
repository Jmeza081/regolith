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

    /**
     * Title Detail's hero, and nothing else. 16:9 at 720p because it is drawn
     * across the FULL width of the window — 2076px on the inner display —
     * where a [THUMB] was being blown up more than six times and looked it.
     *
     * Not one of the [stills]: it is four times a thumb's bytes, and a wall
     * of two hundred tiles has no use for it. It is generated on demand, for
     * the one title you actually opened.
     */
    BACKDROP(1280, 720, "backdrop.jpg"),
    ;

    companion object {
        /** What a grid needs, and what every frame grab, sidecar and mosaic writes together. */
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

    /**
     * Tells apart several images belonging to the same [id], and empty for
     * the owners that have exactly one — which is why adding it changed
     * nothing for [File] and [Folder].
     *
     * It exists for [Moment]: a film has as many moment frames as the user
     * placed marks, so [id] alone cannot address them. Part of the cache
     * path and of the `artwork` table's unique key, so it must be a stable
     * function of the thing it names, never a row id.
     */
    val variant: String get() = ""

    data class File(override val id: Long) : ArtworkOwner {
        override val typeName get() = "file"
    }

    data class Folder(override val id: Long) : ArtworkOwner {
        override val typeName get() = "folder"
    }

    /**
     * One named chapter's own frame: the picture at [startMs] of the film,
     * rather than the film's poster frame. Search draws a point of interest
     * with this so two marks in the same film do not show the same picture
     * under two different clocks.
     *
     * **Keyed by ([fileId], [startMs]), deliberately not by the chapter's
     * row id.** `user_chapters` rows are rewritten wholesale on every save
     * (`UserChapterDao.replaceForFile`), so their ids are regenerated when
     * any one mark in the film is renamed — a row-id key would throw away
     * every frame in a film to rename a single chapter. The time is what the
     * frame actually depends on, so the time is the key: renaming is free,
     * and MOVING a mark misses and re-grabs, which is exactly right because
     * the picture genuinely changed.
     */
    data class Moment(val fileId: Long, val startMs: Long) : ArtworkOwner {
        override val typeName get() = "moment"
        override val id get() = fileId
        override val variant get() = startMs.toString()
    }
}

/**
 * One image the UI wants. This is what a tile hands to Coil as its model;
 * the app's fetcher turns it into bytes from the artwork directory,
 * resolving on a miss (guardrail G5).
 */
data class ArtworkRequest(val owner: ArtworkOwner, val kind: ArtworkKind)
