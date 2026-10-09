package com.regolith.ui.story

import com.regolith.domain.playback.Story
import com.regolith.domain.playback.StoryPace
import com.regolith.ui.library.PictureTile
import com.regolith.ui.util.formatDate

/**
 * A story's page: the album, its pictures in the order they play, which
 * one is up, and how long each stays. The segment's filling is the
 * screen's own, like a scroll position: it moves every frame.
 */
data class StoryUiState(
    /** The album's name, at the top beside its poster. */
    val album: String = "",
    val folderId: Long = 0,
    /** In the order they play, fixed when the story starts. */
    val pictures: List<PictureTile> = emptyList(),
    val index: Int = 0,
    /**
     * Counts every move (on, back, or back on the first picture to start it
     * again), so the segment starts over even when [index] does not change.
     */
    val step: Int = 0,
    /** Paused from its button. A hold pauses too, but only while the finger is down: that is the screen's. */
    val paused: Boolean = false,
    val pace: StoryPace = StoryPace.DEFAULT,
    /** Moving GIFs' own lengths, as they are read, by picture id. */
    val gifMs: Map<Long, Long> = emptyMap(),
    val loaded: Boolean = false,
    /** Past the last picture: the screen closes. */
    val finished: Boolean = false,
) {
    val current: PictureTile? get() = pictures.getOrNull(index)

    /** How long the picture up now stays: the pace, or a GIF's own length when that is longer. */
    val durationMs: Long get() = Story.durationMs(pace, current?.let { gifMs[it.pictureId] })

    /** "4 of 12 · 2 Aug 2024": where it is, and when the picture was taken (or its file's date). */
    val position: String
        get() {
            val picture = current ?: return ""
            return "${index + 1} of ${pictures.size} · ${formatDate(picture.takenAtMs ?: picture.modifiedAtMs)}"
        }
}
