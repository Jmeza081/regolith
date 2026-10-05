package com.regolith.domain.transfer

import com.regolith.domain.media.MediaFileTypes
import com.regolith.domain.model.formatByteSize

/** Where an upload is in its life: [TransferStatus], pointing the other way. */
enum class UploadStatus {
    /** Waiting for its turn, or for the network. */
    QUEUED,
    RUNNING,

    /** The server went quiet mid-file. Resumes by itself from the bytes already sent. */
    PAUSED,

    /** On the share — or deliberately not sent ([UploadCause.ALREADY_THERE], [UploadCause.SKIPPED]). */
    DONE,

    /** Gave up; the [UploadCause] says why. */
    FAILED,
}

/**
 * Why an upload stopped, or why it never needed to start.
 *
 * Named per cause rather than a bare "failed" because each one has a
 * different fix — wait, free some space, fix a permission on the NAS, type
 * the password again — and a row that only said "failed" would leave the
 * user guessing which.
 */
enum class UploadCause {
    /** PAUSED: the server stopped answering. Nothing to do but wait; the queue retries by itself. */
    SHARE_DROPPED,

    /** FAILED: no room on the share. `causeBytes` is how much more this file needs. */
    SHARE_FULL,

    /** FAILED: this login may not create files in that folder. */
    READ_ONLY,

    /** FAILED: the server stopped accepting the saved password. */
    SIGN_IN,

    /** FAILED: the file can no longer be read on the phone — deleted, or access withdrawn. Nothing to retry. */
    SOURCE_GONE,

    /** FAILED: the destination folder is not on the share any more. Nothing to retry. */
    FOLDER_GONE,

    /** DONE: a file with this name and size was already there — the same file, uploaded twice. */
    ALREADY_THERE,

    /** DONE: the name was taken by a different file and the user chose to skip it. */
    SKIPPED,

    /** FAILED: stopped from the notification. "Try again" picks up from the bytes already sent. */
    CANCELLED,

    OTHER,
}

/**
 * What happens to a picked file whose name is already taken in the
 * destination by a DIFFERENT file (same name and same size is the same file,
 * and is skipped without asking). Asked once per batch, not per file.
 */
enum class ConflictPolicy {
    /** The upload arrives as "name (1).ext". The default, because nothing is lost. */
    KEEP_BOTH,

    /** The upload takes the name and the file that had it is gone. Never silent: only ever chosen in the sheet. */
    REPLACE,

    /** Not sent. */
    SKIP,
}

/** A file picked on the phone, described before anything is sent. */
data class PickedFile(
    /** The `content://` address another app handed us. Opaque: only the phone can open it. */
    val uri: String,
    /** Already made fit for a share ([com.regolith.domain.fileops.FileNames.sanitize]). */
    val name: String,
    /** -1 when the provider would not say. */
    val sizeBytes: Long,
    /** The phone file's own modified time, given to the copy on the share. Null when unknown. */
    val modifiedAtMs: Long?,
)

/** One row of the upload queue, as the screens and the notification see it. */
data class UploadItem(
    val id: Long,
    /** The rows picked together share this, and are counted and messaged together. */
    val batchId: Long,
    val folderId: Long,
    val sourceUri: String,
    /** The name it has — or will have — on the share. */
    val name: String,
    /** -1 when unknown. */
    val sizeBytes: Long,
    val bytesDone: Long,
    val status: UploadStatus,
    val cause: UploadCause?,
    /** The cause's number: how much more room a full share needs. */
    val causeBytes: Long?,
) {
    val isVideo: Boolean get() = MediaFileTypes.isVideo(name)

    /** Still owed: waiting, going, or waiting for the server. */
    val live: Boolean get() = status == UploadStatus.QUEUED || status == UploadStatus.RUNNING || status == UploadStatus.PAUSED

    /** Failed in a way "Try again" can fix. A file gone from the phone, or a folder gone from the share, cannot be. */
    val retryable: Boolean
        get() = status == UploadStatus.FAILED && cause != UploadCause.SOURCE_GONE && cause != UploadCause.FOLDER_GONE

    /** Arrived because we sent it — not skipped, not already there. */
    val uploaded: Boolean get() = status == UploadStatus.DONE && cause == null

    /** 0..1 of the bytes, or null when the size is unknown. */
    val fraction: Float? get() = if (sizeBytes > 0) (bytesDone.toFloat() / sizeBytes).coerceIn(0f, 1f) else null
}

/** How a clash in the destination is judged, and what a kept-both upload is called. */
object UploadNames {
    /**
     * Same name AND same size: the same file uploaded twice, which is
     * skipped rather than asked about. An unknown size never matches — a
     * guess is not good enough to decide a file needs no sending.
     */
    fun isSameFile(existingSize: Long, pickedSize: Long): Boolean = pickedSize >= 0 && existingSize == pickedSize

    /**
     * The first free name: "IMG_1.jpg" taken becomes "IMG_1 (1).jpg", then
     * "(2)", the way Windows and Drive number copies. Compared without case,
     * because SMB names are case-insensitive and "img_1.JPG" IS "IMG_1.jpg"
     * to the server.
     */
    fun keepBoth(name: String, taken: Set<String>): String {
        val lower = taken.mapTo(HashSet()) { it.lowercase() }
        if (name.lowercase() !in lower) return name
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var n = 1
        while (true) {
            val candidate = "$base ($n)$ext"
            if (candidate.lowercase() !in lower) return candidate
            n++
        }
    }
}

/**
 * Some rows of the queue — one folder's, or every live batch's — reduced to
 * the numbers a progress line needs.
 *
 * A BATCH is what one pick added. Once any file of a batch is still owed,
 * the whole batch counts, finished files included: that is what lets the
 * line say "3 of 4" rather than restarting at "1 of 2" halfway through.
 *
 * @property position one-based: the file being sent now, "2" in "2 of 4".
 * @property folderId the one folder they are all going to, or null when more than one.
 * @property paused the server went quiet and nothing is moving.
 */
data class UploadTally(
    val files: Int,
    val position: Int,
    val bytesDone: Long,
    val bytesTotal: Long,
    val folderId: Long?,
    val paused: Boolean,
) {
    /** How far through, in BYTES — the same reasoning as the download queue's [QueueProgress]. */
    val fraction: Float? get() = if (bytesTotal > 0) (bytesDone.toFloat() / bytesTotal).coerceIn(0f, 1f) else null

    companion object {
        /** The live batches in [items], or null when nothing is owed. */
        fun of(items: List<UploadItem>): UploadTally? {
            val liveBatches = items.filter { it.live }.mapTo(HashSet()) { it.batchId }
            if (liveBatches.isEmpty()) return null
            val batch = items.filter { it.batchId in liveBatches }
            // A failed file's bytes are not coming, so they leave the total
            // rather than holding the bar short of the end for good.
            val counted = batch.filterNot { it.status == UploadStatus.FAILED }
            val finished = batch.count { it.status == UploadStatus.DONE || it.status == UploadStatus.FAILED }
            return UploadTally(
                files = batch.size,
                position = (finished + 1).coerceAtMost(batch.size),
                bytesDone = counted.sumOf { if (it.status == UploadStatus.DONE) it.sizeBytes.coerceAtLeast(0) else it.bytesDone },
                bytesTotal = counted.sumOf { it.sizeBytes.coerceAtLeast(0) },
                folderId = batch.map { it.folderId }.distinct().singleOrNull(),
                paused = batch.any { it.status == UploadStatus.PAUSED } && batch.none { it.status == UploadStatus.RUNNING },
            )
        }
    }
}

/**
 * Every sentence the upload queue says, in one place: the rows in Browse,
 * the section above them, the line over the nav pill, the notification and
 * the message at the end. Pure, so the wording is tested rather than
 * eyeballed in a notification shade — and so the notification and the row
 * can never describe the same file two ways.
 *
 * [server] is the server's display name ("TOWER"), [folder] the destination
 * folder's name ("Lisbon 2026").
 */
object UploadWording {
    private fun bytes(n: Long) = formatByteSize(n.coerceAtLeast(0))

    /** The line under a file's name in Browse. */
    fun status(item: UploadItem, server: String, folder: String): String = when (item.status) {
        UploadStatus.QUEUED -> when {
            item.bytesDone > 0 && item.sizeBytes > 0 -> "Waiting · ${bytes(item.bytesDone)} of ${bytes(item.sizeBytes)} sent"
            item.sizeBytes >= 0 -> "Waiting · ${bytes(item.sizeBytes)}"
            else -> "Waiting"
        }
        UploadStatus.RUNNING -> if (item.sizeBytes > 0) {
            "${bytes(item.bytesDone)} of ${bytes(item.sizeBytes)} · ${percent(item)}%"
        } else {
            "${bytes(item.bytesDone)} sent"
        }
        UploadStatus.PAUSED -> if (item.bytesDone > 0 && item.sizeBytes > 0) {
            "Paused at ${bytes(item.bytesDone)} of ${bytes(item.sizeBytes)} · resumes on its own"
        } else {
            "Waiting for $server · resumes on its own"
        }
        UploadStatus.DONE -> when (item.cause) {
            UploadCause.ALREADY_THERE -> "Already on $server · skipped"
            UploadCause.SKIPPED -> "Skipped · the name was taken"
            else -> if (item.sizeBytes >= 0) "Uploaded · ${bytes(item.sizeBytes)}" else "Uploaded"
        }
        UploadStatus.FAILED -> failure(item, server, folder)
    }

    /** Why a file did not make it, in the words the fix needs. */
    fun failure(item: UploadItem, server: String, folder: String): String = when (item.cause) {
        UploadCause.SHARE_FULL -> item.causeBytes?.let { "$server is full · needs ${bytes(it)} more" } ?: "$server is full"
        UploadCause.READ_ONLY -> "$folder is read-only for this login"
        UploadCause.SIGN_IN -> "$server refused the password · check it in Settings"
        UploadCause.SOURCE_GONE -> "No longer on this phone"
        UploadCause.FOLDER_GONE -> "$folder isn't on $server any more"
        UploadCause.CANCELLED -> if (item.bytesDone > 0 && item.sizeBytes > 0) {
            "Stopped at ${bytes(item.bytesDone)} of ${bytes(item.sizeBytes)}"
        } else {
            "Stopped"
        }
        else -> "Couldn't upload"
    }

    /** Floor, not round: 99.6% is not done, and "100%" beside an unfinished file would be a lie. */
    private fun percent(item: UploadItem): Int = ((item.bytesDone * 100) / item.sizeBytes.coerceAtLeast(1)).toInt().coerceIn(0, 100)

    /** The eyebrow over a folder's upload rows. */
    fun sectionLabel(items: List<UploadItem>, server: String): String {
        val tally = UploadTally.of(items)
        return when {
            tally != null && tally.paused -> "Waiting for $server"
            tally != null -> "Uploading · ${tally.position} of ${tally.files}"
            items.any { it.status == UploadStatus.FAILED } -> "Uploaded ${items.count { it.status == UploadStatus.DONE }} of ${items.size}"
            else -> "Just uploaded"
        }
    }

    /** "4 uploaded", or "3 uploaded · 1 skipped": the batch folded into one row once nothing is left to do. */
    fun summaryTitle(items: List<UploadItem>): String {
        val sent = items.count { it.uploaded }
        val skipped = items.count { it.status == UploadStatus.DONE && !it.uploaded }
        return if (skipped == 0) "$sent uploaded" else "$sent uploaded · $skipped skipped"
    }

    /** "3 photos · 1 video · 625 MB": what arrived, by kind. */
    fun summaryMeta(items: List<UploadItem>): String {
        val sent = items.filter { it.uploaded }
        val videos = sent.count { it.isVideo }
        val photos = sent.count { !it.isVideo && MediaFileTypes.isPhoto(it.name) }
        val others = sent.size - videos - photos
        return listOfNotNull(
            count(photos, "photo", "photos"),
            count(videos, "video", "videos"),
            count(others, "file", "files"),
            sent.sumOf { it.sizeBytes.coerceAtLeast(0) }.takeIf { it > 0 }?.let { bytes(it) },
        ).joinToString(" · ")
    }

    /**
     * Said under the summary when something that is not a video arrived.
     * It is listed at the foot of the folder, with its other files, not up
     * here among the videos, and the user should not have to wonder where
     * it went.
     */
    fun otherFilesNote(items: List<UploadItem>): String? {
        val hidden = items.filter { it.uploaded && !it.isVideo }
        if (hidden.isEmpty()) return null
        val allPhotos = hidden.all { MediaFileTypes.isPhoto(it.name) }
        val what = when {
            hidden.size == 1 && allPhotos -> "The photo is"
            hidden.size == 1 -> "The file is"
            allPhotos -> "The photos are"
            else -> "The other files are"
        }
        return "$what below, with this folder's other files."
    }

    /** The line above the nav pill, from anywhere in the app. [folder] is null when the uploads go to more than one. */
    fun tierLine(tally: UploadTally, server: String?, folder: String?): String = when {
        tally.paused -> "Waiting for ${server ?: "the share"} · picks up where it stopped"
        tally.files == 1 -> if (folder != null) "Uploading to $folder" else "Uploading 1 file"
        folder != null -> "Uploading ${tally.files} to $folder · ${tally.position} of ${tally.files}"
        else -> "Uploading ${tally.files} files · ${tally.position} of ${tally.files}"
    }

    /** The notification's title: what is going where. */
    fun notificationTitle(tally: UploadTally, server: String?, folder: String?): String = when {
        tally.paused -> "Waiting for ${server ?: "the share"}"
        folder != null -> if (tally.files == 1) "Uploading to $folder" else "Uploading ${tally.files} to $folder"
        else -> "Uploading ${tally.files} files"
    }

    /** The notification's text: which file, or why nothing is moving. */
    fun notificationText(tally: UploadTally, current: String?): String = when {
        tally.paused -> "Picks up where it stopped when the share is back"
        tally.files > 1 && current != null -> "${QueueProgress.label(tally.position, tally.files)} · $current"
        else -> current.orEmpty()
    }

    /** What the message capsule says when a batch has nothing left to send. */
    fun batchMessage(items: List<UploadItem>, folder: String): BatchMessage {
        // A Stop from the notification is the user's own doing: said plainly, never in red.
        val stopped = items.count { it.status == UploadStatus.FAILED && it.cause == UploadCause.CANCELLED }
        val failed = items.count { it.status == UploadStatus.FAILED } - stopped
        if (stopped > 0 && failed == 0) {
            val sent = items.count { it.uploaded }
            return BatchMessage(if (sent == 0) "Uploads stopped" else "Stopped · $sent of ${items.size} uploaded", failed = false)
        }
        if (failed > 0) {
            return BatchMessage(
                text = if (items.size == 1) "${items.first().name} didn't upload" else "$failed of ${items.size} didn't upload",
                failed = true,
            )
        }
        val sent = items.count { it.uploaded }
        val skipped = items.size - sent
        val text = when {
            sent == 0 -> if (skipped == 1) "Already in $folder" else "All $skipped were already in $folder"
            skipped == 0 -> if (sent == 1) "Uploaded to $folder" else "Uploaded $sent to $folder"
            else -> "Uploaded $sent to $folder · $skipped already there"
        }
        return BatchMessage(text, failed = false)
    }

    /** "1 upload needs you" / "3 uploads need you": the notification left behind when a run ends with failures. */
    fun attentionTitle(failed: Int): String = if (failed == 1) "1 upload needs you" else "$failed uploads need you"

    private fun count(n: Int, one: String, many: String): String? = when (n) {
        0 -> null
        1 -> "1 $one"
        else -> "$n $many"
    }
}

/** A finished batch, in one line. [failed] picks the capsule's red. */
data class BatchMessage(val text: String, val failed: Boolean)

/**
 * What an upload row asks the image loader for: the phone's own thumbnail
 * of a picked file. A value of its own rather than the bare address, so
 * the loader can be taught what it means (`data/transfer/UploadThumbFetcher`).
 */
data class UploadThumb(val uri: String)
