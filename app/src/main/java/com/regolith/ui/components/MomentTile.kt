package com.regolith.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.regolith.domain.artwork.ArtworkKind
import com.regolith.domain.artwork.ArtworkOwner
import com.regolith.domain.artwork.ArtworkRequest
import com.regolith.ui.util.formatClock

/**
 * One moment or chapter as a tile: its frame ([ArtworkOwner.Moment], the
 * picture Search caches for it too) with its start time over it, and its
 * [name] under it. [video] names the video it is in, under the name, where
 * moments from several videos sit together (a collection's Moments tab); a
 * video's own page leaves it out. A tap plays from the time.
 *
 * [frameMs] is where the picture is taken from: a named mark's own time, the
 * default, or a quarter into a chapter's part, as the player's Chapters sheet
 * takes it ([com.regolith.domain.playback.ChapterStills]).
 *
 * Used by a collection's Moments tab and a video's page.
 */
@Composable
fun MomentTile(
    fileId: Long,
    startMs: Long,
    name: String,
    onPlay: () -> Unit,
    testTag: String,
    modifier: Modifier = Modifier,
    video: String? = null,
    frameMs: Long = startMs,
) {
    MediaTile(
        artwork = ArtworkRequest(ArtworkOwner.Moment(fileId, frameMs), ArtworkKind.THUMB),
        kind = ArtworkKind.THUMB,
        title = name,
        meta = video,
        chip = formatClock(startMs),
        fallbackLabel = video ?: name,
        onClick = onPlay,
        testTag = testTag,
        modifier = modifier,
    )
}
