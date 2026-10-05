package com.regolith.data.transfer

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.regolith.MainActivity
import com.regolith.R
import com.regolith.data.RegolithNotifications
import com.regolith.data.db.FolderDao
import com.regolith.data.db.ServerDao
import com.regolith.data.db.ShareDao
import com.regolith.data.db.UploadDao
import com.regolith.data.db.UploadEntity
import com.regolith.data.repository.LibraryRepository
import com.regolith.domain.media.MediaFileTypes
import com.regolith.domain.transfer.QueueProgress
import com.regolith.domain.transfer.UploadCause
import com.regolith.domain.transfer.UploadStatus
import com.regolith.domain.transfer.UploadTally
import com.regolith.domain.transfer.UploadWording
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * The upload queue (P16): one job, one file at a time, one notification.
 *
 * All the deciding is in [UploadRunner]; this is the part only a worker can
 * do — asking Room for the next row after every file (so a pick made while
 * a batch is going joins it), running as a foreground job so the upload
 * outlives the app, and keeping the notification honest.
 *
 * The share dropping out ends the run with `Result.retry()`, and WorkManager
 * comes back with exponential backoff and a network constraint — "resumes
 * on its own". A file that fails for a reason of its own (no room, gone
 * from the phone) does not stop the others; the run ends with a "needs you"
 * notification instead, which stays after the progress one has gone.
 */
@HiltWorker
class UploadQueueWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val uploads: UploadDao,
    private val runner: UploadRunner,
    private val folders: FolderDao,
    private val shares: ShareDao,
    private val servers: ServerDao,
    private val library: LibraryRepository,
) : CoroutineWorker(context, params) {

    /** The file going now, for the notification's second line. */
    private var current: UploadEntity? = null
    private var lastNotifiedAtMs = 0L

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        // The batches this run sent from, for the "needs you" at the end.
        val touched = HashSet<Long>()
        // Folders that received anything but a video: listed once at the end
        // of the run, not per file, since thirty photos into one folder need
        // one look.
        val listAtEnd = HashSet<Long>()
        try {
            notify(force = true)
            while (true) {
                if (isStopped) return@withContext stopped()
                val next = uploads.nextQueued() ?: break
                touched += next.batchId
                current = next
                when (val outcome = runner.send(next) { row -> current = row; notify() }) {
                    UploadRunner.Outcome.Paused -> return@withContext Result.retry()
                    is UploadRunner.Outcome.Done ->
                        if (MediaFileTypes.isVideo(outcome.name)) listAgain(outcome.folderId) else listAtEnd += outcome.folderId
                    else -> Unit
                }
                notify(force = true)
            }
        } catch (e: CancellationException) {
            if (stopReason == WorkInfo.STOP_REASON_CANCELLED_BY_APP) withContext(NonCancellable) { stopAll() }
            throw e
        }
        // The listing is what puts the files in Browse, among the folder's
        // other files, and what checks its pictures against the share, so a
        // folder.jpg that just replaced the old one shows on the next draw.
        listAtEnd.forEach { listAgain(it) }
        leaveAttention(touched)
        // A row picked between the last file and here would otherwise sit
        // QUEUED, because KEEP drops an enqueue while this job still runs.
        if (uploads.nextQueued() != null) Result.retry() else Result.success()
    }

    /**
     * Stop, from the notification — the one way anyone in the app cancels
     * this job (the scheduler never replaces a running one). Everything
     * owed becomes "Stopped" with Try again, rather than vanishing.
     */
    private suspend fun stopAll() {
        uploads.cancelActive(System.currentTimeMillis())
    }

    /** Stopped between files: by the notification, or by the system (quota, constraints). */
    private suspend fun stopped(): Result {
        if (stopReason == WorkInfo.STOP_REASON_CANCELLED_BY_APP) {
            withContext(NonCancellable) { stopAll() }
            return Result.success()
        }
        return Result.retry()
    }

    /**
     * A video that landed is a video the library does not know about yet.
     * One listing of its folder makes it a row like any other — in Browse,
     * in Library, in Search — without waiting for the next scan. Any other
     * file is listed to appear in Browse with the folder's other files, and
     * an image for its pictures too: the listing is where a folder.jpg that
     * changed is noticed ([com.regolith.data.artwork.ArtworkRepository.onFolderListed]).
     * Best effort: the next visit to the folder lists it anyway.
     */
    private suspend fun listAgain(folderId: Long) {
        try {
            library.refreshFolder(folderId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.i(TAG, "uploaded into $folderId but could not list it again: ${e.message}")
        }
    }

    // ── The notifications ───────────────────────────────────────────────

    /** What the progress notification says, worked out from the rows. */
    private data class Snapshot(val title: String, val text: String, val permille: Int, val indeterminate: Boolean, val folderId: Long?)

    private suspend fun snapshot(): Snapshot {
        val rows = uploads.all()
        val tally = UploadTally.of(rows.map { it.toItem() })
            ?: return Snapshot("Uploading", "", 0, indeterminate = true, folderId = current?.folderId)
        val folderName = tally.folderId?.let { folders.byId(it)?.name }
        val server = serverName(current?.folderId ?: tally.folderId ?: rows.firstOrNull { it.toItem().live }?.folderId)
        return Snapshot(
            title = UploadWording.notificationTitle(tally, server, folderName),
            text = UploadWording.notificationText(tally, current?.targetName),
            permille = QueueProgress.permille(tally.bytesDone, tally.bytesTotal),
            indeterminate = tally.bytesTotal <= 0,
            folderId = tally.folderId,
        )
    }

    private suspend fun serverName(folderId: Long?): String? {
        val folder = folderId?.let { folders.byId(it) } ?: return null
        val share = shares.byId(folder.shareId) ?: return null
        return servers.byId(share.serverId)?.name
    }

    /**
     * WorkManager asks for this BEFORE `doWork` on an expedited request,
     * which is what puts the notification up on the tap, with the batch's
     * size already in it.
     */
    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(runCatching { snapshot() }.getOrElse { Snapshot("Uploading", "", 0, true, null) })

    /** At most every [NOTIFICATION_INTERVAL_MS]: the row beat is far too fast for the shade. */
    private suspend fun notify(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastNotifiedAtMs < NOTIFICATION_INTERVAL_MS) return
        lastNotifiedAtMs = now
        runCatching { setForeground(foregroundInfo(snapshot())) }
            .onFailure { if (it is CancellationException) throw it else Log.w(TAG, "no foreground: ${it.message}") }
    }

    private fun foregroundInfo(s: Snapshot): ForegroundInfo {
        ensureChannel()
        val notification = NotificationCompat.Builder(applicationContext, RegolithNotifications.CHANNEL_UPLOADS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(s.title)
            .setContentText(s.text)
            .setProgress(PROGRESS_MAX, s.permille, s.indeterminate)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openFolder(s.folderId))
            .addAction(0, "Stop", WorkManager.getInstance(applicationContext).createCancelPendingIntent(id))
            .build()
        return ForegroundInfo(RegolithNotifications.UPLOADS_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    /**
     * What stays behind when a run ends with files that did not make it.
     * The progress notification leaves with the job; without this, a batch
     * that half-failed while the phone was in a pocket would look like one
     * that finished. A stop the user asked for is not news, so it is left out.
     */
    private suspend fun leaveAttention(batches: Set<Long>) {
        if (batches.isEmpty()) return
        val failed = uploads.all().filter {
            it.batchId in batches && it.status == UploadStatus.FAILED.name && it.cause != UploadCause.CANCELLED.name
        }
        if (failed.isEmpty()) return
        val first = failed.first().toItem()
        val folderName = folders.byId(first.folderId)?.name ?: "the folder"
        val server = serverName(first.folderId) ?: "the share"
        val folderId = failed.map { it.folderId }.distinct().singleOrNull()
        ensureChannel()
        val notification = NotificationCompat.Builder(applicationContext, RegolithNotifications.CHANNEL_UPLOADS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(UploadWording.attentionTitle(failed.size))
            .setContentText("${first.name} · ${UploadWording.failure(first, server, folderName)}")
            .setAutoCancel(true)
            .setContentIntent(openFolder(folderId))
            .build()
        val allowed = ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (allowed) NotificationManagerCompat.from(applicationContext).notify(RegolithNotifications.UPLOADS_ATTENTION_ID, notification)
    }

    private fun ensureChannel() {
        applicationContext.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(RegolithNotifications.CHANNEL_UPLOADS, "Uploads", NotificationManager.IMPORTANCE_LOW),
        )
    }

    /** Tapping either notification opens the folder the files are going to, or Browse when there is more than one. */
    private fun openFolder(folderId: Long?): PendingIntent {
        val intent = Intent(applicationContext, MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_LAUNCHER)
            putExtra(EXTRA_OPEN_UPLOAD_FOLDER, folderId ?: NO_FOLDER)
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            applicationContext, REQUEST_OPEN_UPLOADS, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        /** Set on the launch intent when an upload notification is tapped: the folder to open, or [NO_FOLDER]. */
        const val EXTRA_OPEN_UPLOAD_FOLDER = "regolith.openUploadFolder"

        /** The uploads were going to more than one folder: open Browse itself. */
        const val NO_FOLDER = -1L

        private const val TAG = "Regolith/Upload"
        private const val NOTIFICATION_INTERVAL_MS = 2_000L
        private const val PROGRESS_MAX = 1000

        /** Distinct from the download notification's 0, or one PendingIntent would overwrite the other's extras. */
        private const val REQUEST_OPEN_UPLOADS = 44
    }
}
