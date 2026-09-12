package com.regolith.data.transfer

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * How downloads get run. The repository only knows this interface, so
 * moving from foreground WorkManager jobs to Android's user-initiated data
 * transfer jobs later is one class, not a rewrite.
 *
 * There is no per-file method. What is owed lives in the `transfers` and
 * `download_picks` tables (guardrail G3), and the queue re-reads them after
 * every file — so "start this one" and "start these forty" are the same
 * request: make sure the queue is running.
 */
interface TransferScheduler {
    /** Make sure the queue is draining. Idempotent — safe to call per file or once per batch. */
    fun enqueue()

    /** Stop the drain. The rows are the caller's to settle; this only stops the worker. */
    fun cancelAll()
}

/**
 * One unique job named [WORK_NAME] that drains the whole queue.
 *
 * **Why one job instead of one per file.** Twelve picked files used to mean
 * twelve `TransferWorker`s: twelve parallel 1 MiB SMB read loops through
 * the same NAS the player streams from (so the film you actually want
 * arrives last), and twelve foreground notifications all claiming the same
 * id, each overwriting whatever the last one wrote. One job copies one file
 * at a time and owns one honest notification.
 *
 * **Why `KEEP` is safe here.** Normally KEEP would drop a second batch on
 * the floor. It does not, because the worker asks Room for the next pending
 * row rather than taking a snapshot at launch: rows added while it is
 * running join the batch already in flight. The worker also re-checks for
 * pending work before returning success, which closes the one race KEEP
 * leaves — a row inserted in the instant between the last copy and the
 * job finishing.
 */
@Singleton
class WorkManagerTransferScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) : TransferScheduler {

    override fun enqueue() {
        val request = OneTimeWorkRequestBuilder<TransferQueueWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            // Expedited so the notification appears on the tap rather than whenever
            // WorkManager gets round to it. The fallback matters: expedited quota is
            // finite, and running as ordinary work is better than not running.
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .addTag(TAG)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
    }

    override fun cancelAll() {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }

    companion object {
        const val WORK_NAME = "transfers"
        const val TAG = "regolith-transfers"
    }
}
