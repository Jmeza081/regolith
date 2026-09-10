package com.regolith.data.scan

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.regolith.data.db.ScanRunDao
import com.regolith.data.db.ScanRunEntity
import com.regolith.data.db.ServerDao
import com.regolith.data.db.ShareDao
import com.regolith.domain.media.DemoSource
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Starts library scans and exposes their progress (guardrail G3: progress
 * is rows in `scan_runs`, observed as Flows). One unique WorkManager job
 * per share, so tapping "Scan all" twice does not walk a share twice.
 *
 * Web analogy: enqueueing a background job and subscribing to its status
 * rows, rather than awaiting a promise.
 */
@Singleton
class ScanRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val shareDao: ShareDao,
    private val serverDao: ServerDao,
    private val scanRunDao: ScanRunDao,
) {
    /**
     * Scan every enabled share of [serverId], or of every server when null.
     * The demo library is skipped: there is no host to walk, and failing to
     * reach it would mark it out of reach ([DemoSource]).
     */
    suspend fun scanAll(serverId: Long? = null) {
        val demoServerIds = serverDao.observeAll().first().filter { DemoSource.isDemo(it.host) }.map { it.id }.toSet()
        val shares = shareDao.observeEnabled().first()
            .filter { serverId == null || it.serverId == serverId }
            .filterNot { it.serverId in demoServerIds }
        shares.forEach { enqueue(it.id) }
    }

    fun enqueue(shareId: Long) {
        val request = OneTimeWorkRequestBuilder<ScanWorker>()
            .setInputData(workDataOf(ScanWorker.KEY_SHARE_ID to shareId))
            // Expedited: the user is looking at the Scanning screen; fall
            // back to a normal job when the quota is spent.
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(uniqueName(shareId), ExistingWorkPolicy.KEEP, request)
    }

    fun cancel(shareId: Long) {
        WorkManager.getInstance(context).cancelUniqueWork(uniqueName(shareId))
    }

    /** The latest run per share. */
    fun observeLatest(shareIds: List<Long>): Flow<List<ScanRunEntity>> = scanRunDao.observeLatest(shareIds)

    fun observeRunning(): Flow<List<ScanRunEntity>> = scanRunDao.observeRunning()

    private fun uniqueName(shareId: Long) = "scan-share-$shareId"
}
