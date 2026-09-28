package com.regolith.player

import com.regolith.data.artwork.FrameGrabber
import com.regolith.data.artwork.FrameSourceFactory
import com.regolith.domain.artwork.ArtworkKind
import com.regolith.domain.playback.FrameIndex
import javax.inject.Inject

/**
 * Builds [OnDemandScrubThumbnails] for one file, reading the copy on this
 * device when there is one — the rule playback itself follows, so a
 * downloaded film's frames cost no trip to the share.
 *
 * Two callers: the film player ([PlaybackSession], its scrub previews and
 * chapter walls) and the Shorts panel's strip. One place for "where do this
 * file's frames come from" means the two can never disagree about it.
 */
class ScrubThumbnailsFactory @Inject constructor(
    private val frames: FrameSourceFactory,
    private val grabber: FrameGrabber,
    private val resolver: MediaUriResolver,
    private val local: LocalMedia,
) {
    /** See [OnDemandScrubThumbnails] for what each argument does; the defaults are a film's. */
    fun create(
        fileId: Long,
        durationMs: Long,
        intervalMs: Long = FrameIndex.DEFAULT_INTERVAL_MS,
        frameWidth: Int = ArtworkKind.THUMB.width,
        frameHeight: Int = ArtworkKind.THUMB.height,
    ): ScrubThumbnails = OnDemandScrubThumbnails(
        durationMs = durationMs,
        fileId = fileId,
        grabber = grabber,
        intervalMs = intervalMs,
        frameWidth = frameWidth,
        frameHeight = frameHeight,
    ) {
        // Same rule as playback: the copy on this device first.
        local.fileBlocking(fileId)?.let { return@OnDemandScrubThumbnails frames.openLocal(it) }
        val media = resolver.resolveBlocking(fileId) ?: error("file $fileId is unknown")
        frames.open(media.host, media.credentials, media.share, media.relPath)
    }
}
