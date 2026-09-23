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
    // --- Managing the file itself (P12). The share is someone's media, so
    // every one of these is behind a dialog.
    /** The filename WITH its extension, which the dialogs name and the field edits. */
    val fileName: String = "",
    /** "4.0 GB", for the delete dialog: the size is half of what makes it a decision. */
    val sizeLabel: String = "",
    val renaming: Boolean = false,
    val confirmingDelete: Boolean = false,
    /** Why the last rename or delete did not happen. */
    val fileOpError: String? = null,
    /** The file is gone from the share; the screen has nothing left to show. */
    val deleted: Boolean = false,
    // --- A video the phone already had (Phone storage), not a share file.
    /**
     * True for a phone video. It has no share to keep a copy from, rename on
     * or delete from, so the Keep circle and the share's Manage rows give way
     * to "Hide from Regolith" and "Delete from phone".
     */
    val phone: Boolean = false,
    /** "Movies", for the hide row's promise that the file stays there. */
    val phoneFolder: String = "",
    /**
     * The system's delete sheet, waiting to be launched. An app may not
     * delete a video it did not make, so MediaStore hands back a request
     * the person approves. One-shot: the screen launches it and clears it.
     */
    val phoneDeleteRequest: android.content.IntentSender? = null,
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
