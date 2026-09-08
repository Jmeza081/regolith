package com.regolith.data.repository

import com.regolith.data.db.PlaybackProgressDao
import com.regolith.data.db.PlaybackProgressEntity
import javax.inject.Inject
import javax.inject.Singleton

/** Where you stopped watching. One row per file, overwritten on every save. */
@Singleton
class PlaybackRepository @Inject constructor(
    private val progressDao: PlaybackProgressDao,
) {
    suspend fun progress(fileId: Long): PlaybackProgressEntity? = progressDao.byFile(fileId)

    /**
     * Save a position. The last few percent count as finished so the
     * resume row does not offer "0m left" forever after the credits.
     */
    suspend fun save(fileId: Long, positionMs: Long, durationMs: Long) {
        if (durationMs <= 0) return
        val completed = positionMs >= durationMs * COMPLETED_RATIO
        progressDao.upsert(
            PlaybackProgressEntity(
                fileId = fileId,
                positionMs = if (completed) 0 else positionMs,
                durationMs = durationMs,
                completed = completed,
                updatedAtMs = System.currentTimeMillis(),
            ),
        )
    }

    private companion object {
        const val COMPLETED_RATIO = 0.97
    }
}
