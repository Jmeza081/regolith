package com.regolith.domain.transfer

/** Where a download is in its life. */
enum class TransferStatus {
    /** Waiting for its turn, or for the network. */
    QUEUED,
    RUNNING,
    /** The share went away mid-copy; resumes on its own when it is back. */
    PAUSED,
    DONE,
    /** Gave up; [TransferCause] says why, and "Try again" is offered. */
    FAILED,
}

/** Why a transfer stopped. The design names the cause per row, because each needs a different fix. */
enum class TransferCause {
    /** "TOWER dropped · 2.8/18.4 GB" */
    SHARE_DROPPED,
    /** "No room · 12.1 GB needed" */
    NO_ROOM,
    /** The user cancelled it. */
    CANCELLED,
    OTHER,
}

/**
 * Storage arithmetic for the "no room" check (design section 05: "the
 * share dropping out and the storage cap need different fixes"). Pure so
 * the thresholds are unit-tested.
 */
object StorageCheck {
    /** Keep this much free for the OS and the artwork cache. */
    const val RESERVE_BYTES = 500L * 1024 * 1024

    /** Bytes still to copy for a transfer that has [bytesDone] of [totalBytes]. */
    fun remaining(totalBytes: Long, bytesDone: Long): Long = (totalBytes - bytesDone).coerceAtLeast(0)

    /** True when [freeBytes] can take the rest of the file and leave the reserve. */
    fun hasRoom(freeBytes: Long, totalBytes: Long, bytesDone: Long): Boolean =
        freeBytes - remaining(totalBytes, bytesDone) >= RESERVE_BYTES

    /** What "No room · 12.1 GB needed" reports: the shortfall including the reserve. */
    fun shortfall(freeBytes: Long, totalBytes: Long, bytesDone: Long): Long =
        (remaining(totalBytes, bytesDone) + RESERVE_BYTES - freeBytes).coerceAtLeast(0)
}

/**
 * What the batch notification and the Settings row say about a queue of
 * downloads. Pure so the arithmetic is unit-tested rather than eyeballed in
 * a notification shade.
 */
object QueueProgress {
    /**
     * How far through the batch, in permille (0..1000).
     *
     * Measured in BYTES, not files, and that is the point: a batch whose
     * first file is 40 GB and whose other eleven are 200 MB each would sit
     * at "1 of 12" for an hour if files were the unit, which reads as a
     * stalled download. Permille rather than percent because
     * `NotificationCompat.setProgress` takes an integer max and a 1000-step
     * bar moves visibly on a large file where a 100-step one looks stuck.
     */
    fun permille(bytesDone: Long, bytesTotal: Long): Int {
        if (bytesTotal <= 0L) return 0
        val done = bytesDone.coerceIn(0L, bytesTotal)
        return ((done * 1000L) / bytesTotal).toInt()
    }

    /** "3 of 12". [done] is one-based: the file being copied, not the count finished. */
    fun label(done: Int, total: Int): String = "${done.coerceIn(1, maxOf(total, 1))} of ${maxOf(total, 1)}"

    /**
     * What the notification says while a picked folder is still being
     * walked. The count climbs as the walk finds files, because the total
     * genuinely is not known yet — a folder that was never scanned has no
     * rows to count.
     */
    fun discovering(found: Int): String = if (found <= 0) "Finding files…" else "Finding files… $found"
}
