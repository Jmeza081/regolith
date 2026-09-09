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
