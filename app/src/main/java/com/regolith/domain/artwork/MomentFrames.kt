package com.regolith.domain.artwork

/**
 * The moment frames a film holds, as the CHAPTER side needs to see them.
 *
 * A point of interest is drawn with the frame at its own time, cached under
 * that time ([ArtworkOwner.Moment]), and so is every tile on the player's
 * chapter sheet. So whenever a film's marks are rewritten, the frames no
 * chapter uses any more have to go — and the
 * code that rewrites marks has no other business with the artwork pipeline.
 * This is that one seam, so `ChapterSyncRepository` and
 * `UserChapterRepository` depend on two methods rather than on the resolver,
 * its twelve collaborators and its SMB gateway.
 *
 * Same shape, and the same reason, as
 * `com.regolith.player.ScrubThumbnails`: an interface with a [None] that
 * does nothing, so a unit test can leave it out.
 */
interface MomentFrames {
    /**
     * Forget the frames for [fileId] whose time is not in [keepTimesMs]: the
     * times the film's chapters now take their pictures from
     * ([com.regolith.domain.playback.ChapterStills]).
     *
     * Called after a save, where most marks survive: a renamed mark keeps its
     * time and therefore its picture, and a MOVED one is a miss that re-grabs
     * at the new time. This collects what it moved away from, and the
     * pictures of the chapters the saved set replaced.
     */
    suspend fun pruneMoments(fileId: Long, keepTimesMs: Collection<Long>)

    /** Forget every frame for [fileId], when its marks go altogether. */
    suspend fun dropMoments(fileId: Long)

    /** For tests and for anything that has no cache to keep tidy. */
    object None : MomentFrames {
        override suspend fun pruneMoments(fileId: Long, keepTimesMs: Collection<Long>) = Unit
        override suspend fun dropMoments(fileId: Long) = Unit
    }
}
