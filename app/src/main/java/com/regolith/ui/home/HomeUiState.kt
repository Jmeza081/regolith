package com.regolith.ui.home

import com.regolith.domain.artwork.ArtworkRequest

/** One card in "Continue watching": 16:9 so the frame is recognisable, labelled with time left. */
data class ResumeItem(
    val fileId: Long,
    val name: String,
    val artwork: ArtworkRequest,
    val positionMs: Long,
    val durationMs: Long,
    /** "1080p · last night" */
    val meta: String,
) {
    val testTag get() = "home_resume_$fileId"
    val fraction: Float get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
}

/** One poster in "Newly added". */
data class NewItem(
    val fileId: Long,
    val name: String,
    val artwork: ArtworkRequest,
    val meta: String,
    val unwatched: Boolean = true,
) {
    val testTag get() = "home_new_$fileId"
}

/**
 * Home (design section 04): no source, resume, nothing started, and the
 * refresh line. The resume row's absence is the "nothing started" message.
 */
data class HomeUiState(
    val loaded: Boolean = false,
    val serverNames: List<String> = emptyList(),
    val resume: List<ResumeItem> = emptyList(),
    val newlyAdded: List<NewItem> = emptyList(),
    /** "Reading media · 312 files" while a scan walks a share; null otherwise. */
    val refreshLine: String? = null,
    /** True until the first scan has finished on at least one share. */
    val neverScanned: Boolean = false,
    /** Finished copies on this device, for the "On this device" summary. */
    val downloadsReady: Int = 0,
    val downloadsBytes: Long = 0,
) {
    val hasSource: Boolean get() = serverNames.isNotEmpty()
}
