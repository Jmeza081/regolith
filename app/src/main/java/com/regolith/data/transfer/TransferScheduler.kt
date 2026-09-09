package com.regolith.data.transfer

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * How a download gets run. The repository only knows this interface, so
 * moving from foreground WorkManager jobs to Android's user-initiated
 * data transfer jobs later is one class, not a rewrite.
 */
interface TransferScheduler {
    /** Start or resume the transfer for [fileId]. Idempotent. */
    fun enqueue(fileId: Long)

    fun cancel(fileId: Long)
}

/**
 * One unique WorkManager job per file, needs a network, retries with
 * exponential backoff. `KEEP` means "Try again" on a job that is already
 * queued does nothing rather than starting a second copy.
 */
@Singleton
class WorkManagerTransferScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) : TransferScheduler {
    override fun enqueue(fileId: Long) {
        val request = OneTimeWorkRequestBuilder<TransferWorker>()
            .setInputData(workDataOf(TransferWorker.KEY_FILE_ID to fileId))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(uniqueName(fileId), ExistingWorkPolicy.KEEP, request)
    }

    override fun cancel(fileId: Long) {
        WorkManager.getInstance(context).cancelUniqueWork(uniqueName(fileId))
    }

    private fun uniqueName(fileId: Long) = "transfer-file-$fileId"
}
