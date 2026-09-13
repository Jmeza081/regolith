package com.regolith.domain.playback

/**
 * Where a film's user chapters are, relative to the share (P10). The
 * sidecar on the share is the durable copy and the phone's rows are a
 * cache of it; this is what the Chapters sheet says under "Yours".
 */
enum class ChapterSyncState {
    /** Rows on this phone, nothing on the share: demo library, writing turned off, or never synced. */
    PHONE_ONLY,
    /** Edited here; the file has not been written yet (the share is asleep, or the worker has not run). */
    WAITING,
    /** The share refused the write. Rows stay here; tried again at the next scan. */
    READ_ONLY,
    /** Written from this phone and the file matches. */
    ON_SHARE,
    /** Imported from a file someone or something else wrote; editing makes it yours. */
    FROM_SHARE,
}

/** A one-time thing worth saying on the sheet. Cleared by the next edit or sync. */
enum class ChapterSyncNote {
    /** Both sides had changed; the share's newer file won and replaced the phone's rows. */
    REPLACED_BY_SHARE,
    /** Revert could not delete the file (read-only share); it will come back at the next scan. */
    FILE_STAYS,
    /** The last write failed for a reason other than access. */
    WRITE_FAILED,
    ;

    companion object {
        /** Kept in the row's note column while a write is refused; it reads as [ChapterSyncState.READ_ONLY], not as a note. */
        const val READ_ONLY_NOTE = "READ_ONLY"
    }
}

/** What the session hands the player: the state, or null when the film has no user rows, plus any note. */
data class ChapterSync(val state: ChapterSyncState?, val note: ChapterSyncNote?)
