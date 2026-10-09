package com.regolith.ui.components

import com.regolith.domain.media.MediaFileTypes
import com.regolith.domain.transfer.UploadItem
import com.regolith.domain.transfer.UploadStatus
import com.regolith.domain.transfer.UploadThumb
import com.regolith.domain.transfer.UploadWording
import com.regolith.ui.util.formatBytes

/**
 * Uploads into the folder on screen (P16): the section at the top of its
 * list, where the files are going. Built by [uploadSection] from the queue's
 * rows; the words come from [UploadWording], so the row, the tier and the
 * notification describe a file the same way.
 */
data class UploadSection(
    /** "Uploading · 2 of 4", "Waiting for TOWER", "Uploaded 3 of 4", "Just uploaded". */
    val label: String,
    /** The one thing the eyebrow offers, which depends on where the batch stands. */
    val action: UploadSectionAction,
    /** One per file, in the order they were picked. Empty once the batch has folded into [summary]. */
    val rows: List<UploadRow>,
    /** Everything went: one line instead of a row per file. */
    val summary: UploadSummary?,
    /** "The photos are below, with this folder's other files." */
    val note: String?,
)

/** The eyebrow's action. [label] is what it says. */
enum class UploadSectionAction(val label: String) {
    /** Files are still owed: take them all out of the queue. */
    CANCEL_ALL("Cancel all"),

    /** The share dropped and the queue is waiting it out: stop waiting. */
    TRY_NOW("Try now"),

    /** Nothing is going and something failed that trying again can fix. */
    RETRY_ALL("Try again"),

    /** Nothing is going and nothing can be retried: tidy the section away. */
    CLEAR("Clear"),
}

/** What kind of file a row is, for the glyph shown until (or instead of) its thumbnail. */
enum class UploadKind { VIDEO, PHOTO, FILE }

data class UploadRow(
    val id: Long,
    val name: String,
    val thumb: UploadThumb,
    val kind: UploadKind,
    /** "434 MB of 612 MB · 70%", "TOWER is full · needs 140 MB more". */
    val status: String,
    /** The bar under the status, 0..1; null for none. */
    val progress: Float?,
    /** The bar holds greyed: nothing is moving. */
    val progressMuted: Boolean,
    /** The status says something the user has to act on or wait for: drawn a step brighter. */
    val emphatic: Boolean,
    /** Try again is offered: failed, and trying again can fix it. */
    val canRetry: Boolean,
    /** The ✕: cancels a file still owed, or removes one that finished badly. */
    val canRemove: Boolean,
    /** Whether the ✕ cancels (the file is still owed) or removes (it has finished). */
    val live: Boolean,
) {
    val testTag: String get() = "upload_row_$id"
}

/** A batch that went, folded into one row. */
data class UploadSummary(val title: String, val meta: String, val thumb: UploadThumb, val kind: UploadKind)

/**
 * The question asked when a pick's names are taken — once, for all of them.
 * [clashes] lists every file whose name is taken; only the ones that are
 * NOT [UploadClash.sameFile] are what the three answers are about.
 */
data class UploadQuestion(
    val folderName: String,
    val serverName: String,
    val picked: Int,
    val clashes: List<UploadClash>,
) {
    private val different: Int get() = clashes.count { !it.sameFile }

    /** "2 of the 4 you picked have a name that is taken". */
    val subtitle: String
        get() = if (picked == 1) "A file with that name is already there" else {
            "${clashes.size} of the $picked you picked ${if (clashes.size == 1) "has" else "have"} a name that is taken"
        }

    /** What Keep both would call the first different file: "20260914_190455 (1).mp4". */
    val keepBothNote: String
        get() = clashes.firstOrNull { !it.sameFile }?.let { "Yours arrives as ${it.keptName}" } ?: "Yours arrive with a number added"

    val skipLabel: String get() = if (different == 1) "Skip it" else "Skip them"

    /** What Skip leaves to upload. */
    val skipNote: String
        get() {
            val rest = picked - clashes.size
            return when (rest) {
                0 -> "Nothing else is sent"
                1 -> "Upload the other 1"
                else -> "Upload the other $rest"
            }
        }

    val replaceNote: String
        get() = if (different == 1) "The one on $serverName is overwritten" else "The ones on $serverName are overwritten"
}

/**
 * One taken name in the question. [keptName] is what keeping both would call
 * it, [detail] what is there already.
 */
data class UploadClash(
    val name: String,
    val thumb: UploadThumb,
    val kind: UploadKind,
    val sameFile: Boolean,
    val detail: String,
    val keptName: String,
)

/**
 * The section for a folder's upload rows, or null when there are none.
 *
 * A batch where everything went folds into ONE summary row: the per-file
 * detail has done its job, and four finished rows at the top of a folder
 * would push the folder's own films off the screen. A batch with a failure
 * keeps its rows, because a failure needs its row — its cause, its Try again.
 */
fun uploadSection(items: List<UploadItem>, server: String, folder: String): UploadSection? {
    if (items.isEmpty()) return null
    val live = items.filter { it.live }
    val failed = items.filter { it.status == UploadStatus.FAILED }
    val collapsed = live.isEmpty() && failed.isEmpty()
    val waiting = live.any { it.status == UploadStatus.PAUSED } && live.none { it.status == UploadStatus.RUNNING }
    val action = when {
        waiting -> UploadSectionAction.TRY_NOW
        live.isNotEmpty() -> UploadSectionAction.CANCEL_ALL
        failed.any { it.retryable } -> UploadSectionAction.RETRY_ALL
        else -> UploadSectionAction.CLEAR
    }
    val first = items.firstOrNull { it.uploaded } ?: items.first()
    return UploadSection(
        label = UploadWording.sectionLabel(items, server),
        action = action,
        rows = if (collapsed) emptyList() else items.map { uploadRow(it, server, folder) },
        summary = if (collapsed) {
            UploadSummary(UploadWording.summaryTitle(items), UploadWording.summaryMeta(items), UploadThumb(first.sourceUri), kindOf(first.name))
        } else {
            null
        },
        note = if (collapsed) UploadWording.otherFilesNote(items) else null,
    )
}

private fun uploadRow(item: UploadItem, server: String, folder: String): UploadRow {
    val progress = when (item.status) {
        UploadStatus.RUNNING, UploadStatus.PAUSED -> item.fraction ?: 0f
        // Waiting with a part already sent, or failed partway: the bar shows
        // where it will carry on from.
        UploadStatus.QUEUED -> item.fraction?.takeIf { it > 0f }
        UploadStatus.FAILED -> item.fraction?.takeIf { it > 0f && item.retryable }
        UploadStatus.DONE -> null
    }
    return UploadRow(
        id = item.id,
        name = item.name,
        thumb = UploadThumb(item.sourceUri),
        kind = kindOf(item.name),
        status = UploadWording.status(item, server, folder),
        progress = progress,
        progressMuted = item.status != UploadStatus.RUNNING,
        emphatic = item.status == UploadStatus.FAILED || item.status == UploadStatus.PAUSED,
        canRetry = item.retryable,
        canRemove = item.live || item.status == UploadStatus.FAILED,
        live = item.live,
    )
}

/** A clash as the question shows it. */
fun uploadClash(
    name: String,
    uri: String,
    sameFile: Boolean,
    existingSize: Long,
    keptName: String,
): UploadClash = UploadClash(
    name = name,
    thumb = UploadThumb(uri),
    kind = kindOf(name),
    sameFile = sameFile,
    detail = if (sameFile) "Same name and size · skipped as a copy" else "A different file is there · ${formatBytes(existingSize)}",
    keptName = keptName,
)

fun kindOf(name: String): UploadKind = when {
    MediaFileTypes.isVideo(name) -> UploadKind.VIDEO
    MediaFileTypes.isPhoto(name) -> UploadKind.PHOTO
    else -> UploadKind.FILE
}
