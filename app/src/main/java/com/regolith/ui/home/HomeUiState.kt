package com.regolith.ui.home

import com.regolith.domain.artwork.ArtworkRequest
import com.regolith.domain.display.PostersPerRow
import com.regolith.domain.playback.ChapterFacet

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

/** One poster in "Newly added", and in "On this device", which draws the same tile. */
data class NewItem(
    val fileId: Long,
    val name: String,
    val artwork: ArtworkRequest,
    val meta: String,
    val unwatched: Boolean = true,
    /**
     * What its test tag is built from. The two rows can hold the same film
     * at once — something you downloaded is often something newly added —
     * and two identical tags on one screen is a QA flow tapping whichever
     * it finds first.
     */
    val tag: String = "home_new",
) {
    val testTag get() = "${tag}_$fileId"
}

/**
 * Home (design section 04): first use, resume, nothing started, and the
 * refresh line. The resume row's absence is the "nothing started" message.
 */
data class HomeUiState(
    val loaded: Boolean = false,
    val serverNames: List<String> = emptyList(),
    /**
     * A server other than this phone's own storage, which is always listed
     * once phone access is granted. Without one, Home has nowhere to fill
     * from and says so (its first-use state), even though [hasSource] is true.
     */
    val hasNetworkSource: Boolean = false,
    val resume: List<ResumeItem> = emptyList(),
    val newlyAdded: List<NewItem> = emptyList(),
    /** "Reading media · 312 files" while a scan walks a share; null otherwise. */
    val refreshLine: String? = null,
    /** True until the first scan has finished on at least one share. */
    val neverScanned: Boolean = false,
    /** Finished copies on this device, newest first; the row shows the first few. */
    val onDevice: List<NewItem> = emptyList(),
    /** How many finished copies there are in total, and what they occupy. */
    val downloadsReady: Int = 0,
    val downloadsBytes: Long = 0,
    /**
     * Every name the library's moments carry, A to Z, each with how many
     * videos use it: Home's last section, where a tap opens Search on that
     * name. Empty while spoof mode is on, and on a library nobody has
     * marked yet, which leaves the section out.
     */
    val moments: List<ChapterFacet> = emptyList(),
    /** Settings › Display › Posters per row: the inner display's Newly added wall follows it, as the Library's walls do. */
    val postersPerRow: PostersPerRow = PostersPerRow.DEFAULT,
) {
    val hasSource: Boolean get() = serverNames.isNotEmpty()
}
