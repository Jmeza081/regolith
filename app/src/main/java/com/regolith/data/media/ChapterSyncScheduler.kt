package com.regolith.data.media

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * How pending sidecar writes get run. One idempotent request: what is owed
 * is the set of dirty `chapter_sync` rows (guardrail G3), and the worker
 * re-reads them, so "write this one" and "write them all" are the same ask.
 */
interface ChapterSyncScheduler {
    fun enqueue()
}

/** One unique job, network-constrained, with backoff for a share that is asleep. KEEP is safe: the worker re-queries Room. */
@Singleton
class WorkManagerChapterSyncScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) : ChapterSyncScheduler {
    override fun enqueue() {
        val request = OneTimeWorkRequestBuilder<ChapterSyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
    }

    companion object {
        const val WORK_NAME = "chapter-sync"
    }
}

/** Writes every dirty film's sidecar; asks for a retry when the share is out of reach. */
@HiltWorker
class ChapterSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val sync: ChapterSyncRepository,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = if (sync.syncDirty()) Result.success() else Result.retry()
}
