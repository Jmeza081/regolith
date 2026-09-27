package com.regolith.data.transfer

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * How uploads get run: the upload queue's [TransferScheduler]. The
 * repository knows only this, so moving both queues to Android's
 * user-initiated data transfer jobs later is one class each.
 *
 * There is no per-file method, for the same reason as downloads: what is
 * owed lives in the `uploads` table, and the worker asks for the next row
 * after every file.
 */
interface UploadScheduler {
    /** Make sure the queue is draining. Idempotent — safe per file or once per batch. */
    fun enqueue()

    /**
     * Start draining NOW, skipping any backoff the queue is sitting out after
     * the share dropped ("Try now", "Try again"). A queue that is already
     * running is left alone rather than restarted.
     */
    suspend fun enqueueNow()
}

/**
 * One unique job, [WORK_NAME], separate from the downloads' "transfers".
 *
 * **Why not one worker for both directions.** The download queue earned
 * its single job by keeping a dozen copies from starving the player; one
 * upload beside one download is two streams, which a home NAS takes in its
 * stride. What a shared job would cost is the notification: "Keeping 12
 * videos on this device" and "Uploading 4 to Lisbon 2026" are different
 * sentences about different files, and each queue owns its own.
 *
 * **Why REPLACE only when it is not running.** "Try now" has to cut short
 * WorkManager's backoff, which only replacing the waiting job can do. But
 * replacing a RUNNING job cancels it, and the worker reads an app-side
 * cancel as the notification's Stop — which would mark every upload
 * stopped. So a running queue is kept, and only a waiting one is replaced.
 */
@Singleton
class WorkManagerUploadScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) : UploadScheduler {

    override fun enqueue() = enqueue(ExistingWorkPolicy.KEEP)

    override suspend fun enqueueNow() {
        val running = WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(WORK_NAME).first()
            .any { it.state == WorkInfo.State.RUNNING }
        enqueue(if (running) ExistingWorkPolicy.KEEP else ExistingWorkPolicy.REPLACE)
    }

    private fun enqueue(policy: ExistingWorkPolicy) {
        val request = OneTimeWorkRequestBuilder<UploadQueueWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            // Expedited so the notification appears on the tap; ordinary work
            // when the expedited quota is spent, rather than not at all.
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .addTag(TAG)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, policy, request)
    }

    companion object {
        const val WORK_NAME = "uploads"
        const val TAG = "regolith-uploads"
    }
}
