package com.regolith.ui.util

import com.regolith.domain.playback.ChapterWriteOutcome

/**
 * What to say once chapters are saved: where they ended up. Shared by the
 * chapter editor's Save and the quick name sheet, so one outcome never gets
 * two different sentences.
 */
object ChapterMessages {

    /** The editor's line: "Saved to the share", "Saved on this phone — …". */
    fun saved(outcome: ChapterWriteOutcome): String = when (outcome) {
        ChapterWriteOutcome.WRITTEN -> "Saved to the share"
        ChapterWriteOutcome.READ_ONLY -> "Saved on this phone — the share is read-only"
        ChapterWriteOutcome.UNREACHABLE -> "Saved on this phone — the share is out of reach, so it will be written later"
        ChapterWriteOutcome.PHONE_ONLY -> "Saved on this phone"
        ChapterWriteOutcome.FAILED -> "Saved on this phone — the share refused the file; it will be tried again"
    }

    /**
     * The quick name sheet's line, which names what was saved first:
     * "“Car chase” at 24:13 · saved to the share". An unnamed mark reads
     * "Mark at 24:13 · …".
     */
    fun named(title: String?, atMs: Long, outcome: ChapterWriteOutcome): String {
        val what = if (title != null) "“$title” at ${formatClock(atMs)}" else "Mark at ${formatClock(atMs)}"
        return "$what · " + saved(outcome).replaceFirstChar { it.lowercase() }
    }
}
