package com.regolith.data.scan

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.regolith.MainActivity
import com.regolith.R
import com.regolith.data.RegolithNotifications
import com.regolith.data.db.FolderEntity
import com.regolith.data.db.ScanRunDao
import com.regolith.data.db.ScanRunEntity
import com.regolith.data.artwork.ArtworkPrefetcher
import com.regolith.data.repository.LibraryRepository
import com.regolith.domain.smb.SmbFailure
import com.regolith.domain.smb.isAboutTheServer
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
    private val chapterSync: com.regolith.data.media.ChapterSyncScheduler,
) : CoroutineWorker(context, params) {

    /**
     * What the notification says, mirrored from the `scan_runs` row.
     *
     * The row is still the source of truth every screen reads (guardrail
     * G3); this is a copy held in memory because [getForegroundInfo] can be
     * called before the walk has written its first row, and a notification
     * cannot await a Flow.
     */
    private var progress = Progress()
    private var lastNotifiedAtMs = 0L

    override suspend fun doWork(): Result {
        val shareId = inputData.getLong(KEY_SHARE_ID, -1)
        if (shareId < 0) return Result.failure()
        notify(force = true)

        var run = ScanRunEntity(
            shareId = shareId, status = ScanRunEntity.RUNNING, foldersDone = 0, filesFound = 0,
            currentPath = "", startedAtMs = System.currentTimeMillis(), finishedAtMs = null, error = null,
        )
        run = run.copy(id = scanRunDao.insert(run))
        var lastWrite = 0L

        return try {
            val root = library.rootFolder(shareId)
            // The share's own name, so a phone with three sources says WHICH
            // one it is reading rather than "the share".
            progress = progress.copy(shareName = root.name)
            notify(force = true)
            val queue = ArrayDeque<FolderEntity>().apply { add(root) }
            var folders = 0
            var files = 0
            var skipped = 0
            while (queue.isNotEmpty()) {
                val folder = queue.removeFirst()
                // One folder the server will not hand over is not a reason to
                // abandon the share. A walk is thousands of listings, and any
                // of them can fail for reasons that say nothing about the rest
                // — a folder deleted since its parent was listed, an ACL this
                // login cannot read, a name the server will not open. Before
                // this, the first of those ended the scan and the library kept
                // whatever fraction had been reached.
                //
                // Only a failure about the SERVER stops the walk, and by the
                // time one reaches here the listing has already been retried
                // (LibraryRepository.listWithRetry).
                val outcome = try {
                    library.refreshFolder(folder.id)
                } catch (e: SmbFailure) {
                    if (e.isAboutTheServer) throw e
                    Log.w(TAG, "skipping '${folder.relPath}': ${e.message}")
                    skipped++
                    continue
                }
                folders++
                files += outcome.fileCount
                queue.addAll(outcome.subfolders)
                val now = System.currentTimeMillis()
                if (now - lastWrite > PROGRESS_INTERVAL_MS) {
                    lastWrite = now
                    run = run.copy(foldersDone = folders, filesFound = files, currentPath = folder.relPath)
                    scanRunDao.update(run)
                    progress = progress.copy(folders = folders, files = files, path = folder.relPath)
                    notify()
                }
            }
            if (skipped > 0) Log.w(TAG, "share $shareId: $skipped folder(s) could not be read; the rest of the walk finished")
            scanRunDao.update(run.copy(status = ScanRunEntity.DONE, foldersDone = folders, filesFound = files, currentPath = "", finishedAtMs = System.currentTimeMillis()))
            library.markScanned(shareId)
            // The walk knows what to make from the rows this just wrote, so
            // it starts here rather than being something the user has to ask
            // for. It skips whatever is already cached, so a rescan that
            // found nothing new costs a pass over the table and no network.
            artwork.enqueue(shareId)
            // Chapters edited while the share was asleep or refusing writes get another go (P10).
            chapterSync.enqueue()
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

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(progress)

    /**
     * Push the notification, at most every [NOTIFICATION_INTERVAL_MS].
     *
     * The row cadence is 400 ms, which is far too fast for the shade. Same
     * throttle, same reasoning, as `TransferQueueWorker.notify`.
     */
    private suspend fun notify(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastNotifiedAtMs < NOTIFICATION_INTERVAL_MS) return
        lastNotifiedAtMs = now
        runCatching { setForeground(foregroundInfo(progress)) }
            .onFailure { Log.w(TAG, "no foreground: ${it.message}") }
    }

    /**
     * The scan's row in the shade: what it is reading, how far it has got,
     * and the way out.
     *
     * The bar is INDETERMINATE and always will be. A walk learns the shape
     * of a share by walking it, so there is no total to count towards until
     * the moment there is nothing left to do — a determinate bar here could
     * only lie. The Scanning screen makes the same choice with its sweep.
     */
    private fun foregroundInfo(p: Progress): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(RegolithNotifications.CHANNEL_SCAN, "Library scan", NotificationManager.IMPORTANCE_LOW))
        val title = if (p.shareName.isEmpty()) "Reading the share" else "Reading ${p.shareName}"
        val text = when {
            p.files == 0 && p.folders == 0 -> "Regolith keeps the list on this device. Nothing is copied off the share."
            else -> listOf("%,d files".format(p.files), "/" + p.path.ifEmpty { "…" }).joinToString(" · ")
        }
        val notification = NotificationCompat.Builder(applicationContext, RegolithNotifications.CHANNEL_SCAN)
            .setSmallIcon(R.drawable.ic_splash_wordmark)
            .setContentTitle(title)
            .setContentText(text)
            .setProgress(0, 0, true)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp())
            // A first walk of a big share runs for a while. Whoever is looking
            // at the notification is the person best placed to say "not now";
            // cancelling lands in the CancellationException branch above, which
            // marks the run CANCELLED rather than failed.
            .addAction(0, "Stop", WorkManager.getInstance(applicationContext).createCancelPendingIntent(id))
            .build()
        return ForegroundInfo(RegolithNotifications.SCAN_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    /** Tapping the notification just opens the app where it left off. */
    private fun openApp(): PendingIntent {
        val intent = Intent(applicationContext, MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_LAUNCHER)
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            applicationContext, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** The notification's copy of the walk, kept beside the `scan_runs` row. */
    private data class Progress(
        val shareName: String = "",
        val folders: Int = 0,
        val files: Int = 0,
        val path: String = "",
    )

    companion object {
        const val KEY_SHARE_ID = "shareId"
        private const val TAG = "Regolith/Scan"
        private const val PROGRESS_INTERVAL_MS = 400L
        private const val NOTIFICATION_INTERVAL_MS = 2_000L
    }
}
