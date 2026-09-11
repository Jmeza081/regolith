package com.regolith.data.scan

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.regolith.R
import com.regolith.data.db.FolderEntity
import com.regolith.data.db.ScanRunDao
import com.regolith.data.db.ScanRunEntity
import com.regolith.data.artwork.ArtworkPrefetcher
import com.regolith.data.repository.LibraryRepository
import com.regolith.domain.smb.SmbFailure
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import java.util.ArrayDeque

/**
 * Walks one share top to bottom and reconciles Room with what it finds:
 * folders, playable files, parsed titles, folder kinds. A rescan marks
 * files that vanished as missing, never deletes them (guardrail G3).
 *
 * A `CoroutineWorker` is WorkManager's unit of background work; it runs
 * as a foreground job (with a notification) so Android keeps it alive
 * when the user leaves the app: that is what "Run in the background" on
 * the Scanning screen relies on. Progress goes to `scan_runs` a couple of
 * times a second.
 */
@HiltWorker
class ScanWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val library: LibraryRepository,
    private val scanRunDao: ScanRunDao,
    private val artwork: ArtworkPrefetcher,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val shareId = inputData.getLong(KEY_SHARE_ID, -1)
        if (shareId < 0) return Result.failure()
        runCatching { setForeground(foregroundInfo()) }.onFailure { Log.w(TAG, "no foreground: ${it.message}") }

        var run = ScanRunEntity(
            shareId = shareId, status = ScanRunEntity.RUNNING, foldersDone = 0, filesFound = 0,
            currentPath = "", startedAtMs = System.currentTimeMillis(), finishedAtMs = null, error = null,
        )
        run = run.copy(id = scanRunDao.insert(run))
        var lastWrite = 0L

        return try {
            val root = library.rootFolder(shareId)
            val queue = ArrayDeque<FolderEntity>().apply { add(root) }
            var folders = 0
            var files = 0
            while (queue.isNotEmpty()) {
                val folder = queue.removeFirst()
                val outcome = library.refreshFolder(folder.id)
                folders++
                files += outcome.fileCount
                queue.addAll(outcome.subfolders)
                val now = System.currentTimeMillis()
                if (now - lastWrite > PROGRESS_INTERVAL_MS) {
                    lastWrite = now
                    run = run.copy(foldersDone = folders, filesFound = files, currentPath = folder.relPath)
                    scanRunDao.update(run)
                }
            }
            scanRunDao.update(run.copy(status = ScanRunEntity.DONE, foldersDone = folders, filesFound = files, currentPath = "", finishedAtMs = System.currentTimeMillis()))
            library.markScanned(shareId)
            // The walk knows what to make from the rows this just wrote, so
            // it starts here rather than being something the user has to ask
            // for. It skips whatever is already cached, so a rescan that
            // found nothing new costs a pass over the table and no network.
            artwork.enqueue(shareId)
            Result.success()
        } catch (e: CancellationException) {
            scanRunDao.update(run.copy(status = ScanRunEntity.CANCELLED, finishedAtMs = System.currentTimeMillis()))
            throw e
        } catch (e: SmbFailure) {
            // refreshFolder already marked the server unreachable when that was the cause.
            Log.w(TAG, "scan of share $shareId failed: ${e.message}")
            scanRunDao.update(run.copy(status = ScanRunEntity.FAILED, finishedAtMs = System.currentTimeMillis(), error = e.message))
            Result.failure()
        } catch (e: Exception) {
            Log.e(TAG, "scan of share $shareId crashed", e)
            scanRunDao.update(run.copy(status = ScanRunEntity.FAILED, finishedAtMs = System.currentTimeMillis(), error = e.message ?: e.javaClass.simpleName))
            Result.failure()
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo()

    private fun foregroundInfo(): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Library scan", NotificationManager.IMPORTANCE_LOW))
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_splash_wordmark)
            .setContentTitle("Reading the share")
            .setContentText("Regolith keeps the list on this device. Nothing is copied off the share.")
            .setOngoing(true)
            .build()
        return ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    companion object {
        const val KEY_SHARE_ID = "shareId"
        private const val TAG = "Regolith/Scan"
        private const val CHANNEL_ID = "scan"
        private const val NOTIFICATION_ID = 41
        private const val PROGRESS_INTERVAL_MS = 400L
    }
}
