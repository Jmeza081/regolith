package com.regolith.ui.titledetail

import com.regolith.domain.artwork.ArtworkRequest
import com.regolith.domain.transfer.TransferCause
import com.regolith.domain.transfer.TransferStatus

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
    /** Null when the file is not kept on this device and nothing is in flight. */
    val transfer: TransferView? = null,
    /** Other files in the same title folder (design: "IN THIS COLLECTION"). */
    val siblings: List<SiblingFile> = emptyList(),
)

/** A sibling file in a title folder: its thumb, filename, "1h 56m · 8.4 GB". */
data class SiblingFile(val fileId: Long, val name: String, val meta: String, val resolutionLabel: String, val artwork: ArtworkRequest)

/** The download's state as the detail screen shows it. */
data class TransferView(
    val status: TransferStatus,
    val bytesDone: Long,
    val totalBytes: Long,
    val cause: TransferCause?,
    val causeBytes: Long?,
) {
    val fraction: Float get() = if (totalBytes > 0) (bytesDone.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f
}
