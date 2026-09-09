package com.regolith.ui.titledetail

import com.regolith.domain.artwork.ArtworkRequest

/** Everything Title Detail draws (design section 09). */
data class TitleDetailUiState(
    val loaded: Boolean = false,
    val fileId: Long = 0,
    /** Filename without extension. */
    val title: String = "",
    val artwork: ArtworkRequest? = null,
    /** "1080p", "1h 49m", "3.1 GB", "H.264": in that order, empty ones dropped. */
    val chips: List<String> = emptyList(),
    /** Seconds already watched, for "Resume · 15m left". Null when never started. */
    val progressMs: Long? = null,
    val durationMs: Long? = null,
    /** "/media/Films/The Thing/" */
    val path: String = "",
    /** "H.264 · 1920×1080 · 24 fps"; null while the probe runs, "" if it found nothing. */
    val videoLine: String? = null,
    val audioLine: String? = null,
    val modifiedAtMs: Long = 0,
    val probing: Boolean = false,
    val probeError: String? = null,
)
