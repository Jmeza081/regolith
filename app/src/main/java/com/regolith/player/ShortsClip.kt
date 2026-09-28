package com.regolith.player

import com.regolith.data.db.MediaFileEntity

/**
 * One short, as far as what is made ahead for it is concerned: which file,
 * which VERSION of it — a clip replaced on the share under the same name
 * gets a new strip and a new opening — and how long it runs, which decides
 * where the strip's frames are taken.
 */
data class ShortsClip(val fileId: Long, val sizeBytes: Long, val modifiedAtMs: Long, val durationMs: Long) {
    /** The clip's strip folder on disk ([ShortsFrames]): everything a frame depends on. */
    internal val key: String get() = "$fileId-$sizeBytes-$modifiedAtMs-$durationMs"

    /**
     * What the clip's BYTES depend on ([ShortsOpenings]). Its length is left
     * out: it only decides where frames are taken, and a length measured
     * later must not orphan an opening already on disk.
     */
    internal val contentKey: String get() = "$fileId-$sizeBytes-$modifiedAtMs"
}

/** A library row, as Shorts' warm-up knows it. */
internal fun MediaFileEntity.toShortsClip() = ShortsClip(id, sizeBytes, modifiedAtMs, durationMs ?: 0)
