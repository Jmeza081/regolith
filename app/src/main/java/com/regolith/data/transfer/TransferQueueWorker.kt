package com.regolith.data.transfer

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
import com.regolith.data.db.DownloadPickDao
import com.regolith.data.db.MediaFileDao
import com.regolith.data.db.ServerDao
import com.regolith.data.db.ShareDao
import com.regolith.data.db.TransferDao
import com.regolith.data.db.TransferEntity
import com.regolith.data.repository.LibraryRepository
import com.regolith.data.repository.SourceRepository
import com.regolith.domain.smb.SmbFailure
import com.regolith.domain.smb.SmbGateway
import com.regolith.domain.smb.SmbHost
import com.regolith.domain.transfer.QueueProgress
import com.regolith.domain.transfer.pathCoveredBy
import com.regolith.domain.transfer.StorageCheck
import com.regolith.domain.transfer.TransferCause
import com.regolith.domain.transfer.TransferStatus
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.RandomAccessFile

/**
 * The download queue. One job, two phases, one notification.
 *
 * **Phase 1 — discovery.** Every folder the user picked is walked over SMB
 * (`download_picks`), because a folder nobody ever opened has no file rows
 * to expand into. Files are queued per folder as they are found, so copying
 * starts after the FIRST folder rather than after the last.
 *
 * **Phase 2 — the drain.** One file at a time, resuming from the `.part`
 * file's length, asking Room for the next pending row after each one. One
 * at a time is deliberate: the player reads from the same share through the
 * same connection, and a dozen parallel copies would starve it.
 *
 * The share dropping out pauses the whole batch and asks WorkManager to
 * retry with backoff ("resumes on its own"). No room fails one file for
 * good, with the shortfall in its row, because that needs the user — and
 * the rest of the batch carries on, since a small file may still fit.
 */
@HiltWorker
class TransferQueueWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val transfers: TransferDao,
    private val picks: DownloadPickDao,
    private val mediaFileDao: MediaFileDao,
    private val shareDao: ShareDao,
    private val serverDao: ServerDao,
    private val sources: SourceRepository,
    private val library: LibraryRepository,
    private val gateway: SmbGateway,
    private val store: DownloadStore,
    private val chapterSync: com.regolith.data.media.ChapterSyncRepository,
) : CoroutineWorker(context, params) {

    /** How one file ended. Only [Paused] backs the whole batch off. */
    private enum class Outcome { Done, Failed, Paused }

    /** What the notification is currently saying. Rebuilt at most every [NOTIFICATION_INTERVAL_MS]. */
    private data class Progress(
        val discovering: Boolean = false,
        val found: Int = 0,
        val done: Int = 0,
        val total: Int = 0,
        val name: String = "",
        val bytesDone: Long = 0,
        val bytesTotal: Long = 0,
    )

    private var progress = Progress()
    private var lastNotifiedAtMs = 0L

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        // ── Phase 1: walk the picked folders and queue what is inside them.
        var pending = picks.pendingCount()
        if (pending > 0) {
            progress = progress.copy(discovering = true, total = transfers.activeCount())
            notify(force = true)
        }
        while (pending > 0) {
            if (isStopped) return@withContext stopped()
            val pick = picks.nextUndiscovered() ?: break
            // "This folder, minus these": files the user took back out are
            // not queued, and subtrees they took out are not even listed.
            val skipIds = pick.excludedFileIds.split(',').mapNotNull { it.trim().toLongOrNull() }.toSet()
            val skipPaths = pick.excludedPaths.split('\n').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
            try {
                library.listSubtree(
                    folderId = pick.folderId,
                    onProgress = { found ->
                        progress = progress.copy(found = found)
                        notify()
                    },
                    onFolderListed = { files -> queueRows(files.filterNot { it.id in skipIds }.map { it.id }) },
                    prune = { relPath -> pathCoveredBy(skipPaths, relPath) },
                )
            } catch (e: SmbFailure) {
                // The share went quiet mid-walk. Keep the pick so the retry resumes it.
                Log.w(TAG, "discovery paused on ${pick.relPath}: ${e.message}")
                return@withContext Result.retry()
            }
            val found = library.filesUnder(pick.folderId).size
            picks.markDiscovered(pick.id, found)
            pending = picks.pendingCount()
        }
        progress = progress.copy(discovering = false, total = transfers.activeCount())

        // ── Phase 2: copy, one file at a time.
        var done = 0
        while (true) {
            if (isStopped) return@withContext stopped()
            val next = transfers.nextQueued() ?: break
            val tally = transfers.activeBytes()
            progress = progress.copy(done = done, total = maxOf(transfers.activeCount() + done, done + 1), bytesTotal = tally.total)
            when (copyOne(next)) {
                Outcome.Paused -> return@withContext Result.retry()
                Outcome.Failed -> Unit
                Outcome.Done -> done++
            }
        }

        picks.clear()
        // A row inserted between the last copy and here would otherwise sit QUEUED
        // forever, because `KEEP` drops an enqueue while this job is still running.
        if (transfers.nextQueued() != null || picks.pendingCount() > 0) Result.retry() else Result.success()
    }

    /** Rows for files not already downloaded or queued. Returns how many were added. */
    private suspend fun queueRows(fileIds: List<Long>): Int {
        var added = 0
        val now = System.currentTimeMillis()
        for (id in fileIds) {
            if (transfers.byFile(id) != null) continue
            val file = mediaFileDao.byId(id) ?: continue
            transfers.insert(
                TransferEntity(
                    fileId = id, status = TransferStatus.QUEUED.name, bytesDone = 0, totalBytes = file.sizeBytes,
                    localPath = store.relPathFor(id, file.ext), cause = null, causeBytes = null,
                    createdAtMs = now, updatedAtMs = now, finishedAtMs = null,
                ),
            )
            added++
        }
        return added
    }

    /**
     * Copy one file off the share, resuming where a previous run stopped.
     *
     * Unchanged from the per-file worker this replaces, with one addition:
     * the 500 ms row tick now re-reads the row and gives up on this file if
     * it has gone or is no longer RUNNING. That is how a per-file Cancel
     * still works, now that cancelling a file no longer cancels a worker.
     */
    private suspend fun copyOne(row: TransferEntity): Outcome {
        val fileId = row.fileId
        val file = mediaFileDao.byId(fileId) ?: return abandon(row, "no file row")
        val share = shareDao.byId(file.shareId) ?: return abandon(row, "no share")
        val server = serverDao.byId(share.serverId) ?: return abandon(row, "no server")

        val part = store.partFor(row.localPath)
        part.parentFile?.mkdirs()
        // Trust the file on disk over the row: a crash between a write and
        // the row update leaves the file slightly ahead, never behind.
        var doneBytes = if (part.exists()) part.length() else 0L
        var current = row.copy(
            status = TransferStatus.RUNNING.name, bytesDone = doneBytes,
            cause = null, causeBytes = null, updatedAtMs = System.currentTimeMillis(),
        )
        transfers.update(current)
        progress = progress.copy(name = file.name, bytesDone = doneBytes)
        notify(force = true)

        if (!StorageCheck.hasRoom(store.freeBytes(), row.totalBytes, doneBytes)) {
            val need = StorageCheck.shortfall(store.freeBytes(), row.totalBytes, doneBytes)
            transfers.update(
                current.copy(
                    status = TransferStatus.FAILED.name, cause = TransferCause.NO_ROOM.name,
                    causeBytes = need, updatedAtMs = System.currentTimeMillis(),
                ),
            )
            return Outcome.Failed
        }

        return try {
            val host = SmbHost(server.host, server.port)
            val credentials = sources.credentialsFor(server.id)
            var aborted = false
            gateway.open(host, credentials, share.name, file.relPath).use { src ->
                sources.markReachable(server.id)
                val total = src.size
                if (total != current.totalBytes) current = current.copy(totalBytes = total)
                RandomAccessFile(part, "rw").use { out ->
                    out.seek(doneBytes)
                    val buffer = ByteArray(BUFFER_SIZE)
                    var lastWrite = 0L
                    while (doneBytes < total) {
                        currentCoroutineContext().ensureActive()
                        val n = src.readAt(doneBytes, buffer, 0, minOf(buffer.size.toLong(), total - doneBytes).toInt())
                        if (n <= 0) break
                        out.write(buffer, 0, n)
                        doneBytes += n
                        val now = System.currentTimeMillis()
                        if (now - lastWrite > PROGRESS_INTERVAL_MS) {
                            lastWrite = now
                            // Cancelled from the UI while we were copying: stop, keep the bytes.
                            val live = transfers.byFile(fileId)
                            if (live == null || live.status != TransferStatus.RUNNING.name) {
                                aborted = true
                                return@use
                            }
                            current = current.copy(bytesDone = doneBytes, updatedAtMs = now)
                            transfers.update(current)
                            progress = progress.copy(bytesDone = doneBytes)
                            notify()
                        }
                    }
                }
                if (!aborted && doneBytes < total) throw SmbFailure.Other("The share returned fewer bytes than the file has")
            }
            if (aborted) return Outcome.Failed
            if (!part.renameTo(store.fileFor(row.localPath))) throw java.io.IOException("Could not finish ${row.localPath}")
            transfers.update(
                current.copy(
                    status = TransferStatus.DONE.name, bytesDone = doneBytes,
                    updatedAtMs = System.currentTimeMillis(), finishedAtMs = System.currentTimeMillis(),
                ),
            )
            // The chapter file beside the film comes along (P10); its absence is the common case.
            chapterSync.onDownloaded(fileId, host, credentials, share.name, file.relPath.substringBeforeLast('/', ""), file.name)
            Outcome.Done
        } catch (e: CancellationException) {
            // Stopped by WorkManager (the user tapped Stop, or the system reclaimed us).
            // Back to QUEUED so the bytes are resumed rather than re-fetched.
            transfers.byFile(fileId)?.let {
                if (it.status == TransferStatus.RUNNING.name) {
                    transfers.update(it.copy(status = TransferStatus.QUEUED.name, bytesDone = doneBytes, updatedAtMs = System.currentTimeMillis()))
                }
            }
            throw e
        } catch (e: SmbFailure) {
            Log.w(TAG, "transfer $fileId paused at $doneBytes: ${e.message}")
            sources.markUnreachable(server.id)
            transfers.update(
                current.copy(
                    status = TransferStatus.PAUSED.name, bytesDone = doneBytes,
                    cause = TransferCause.SHARE_DROPPED.name, updatedAtMs = System.currentTimeMillis(),
                ),
            )
            Outcome.Paused
        } catch (e: java.io.IOException) {
            Log.w(TAG, "transfer $fileId failed at $doneBytes: $e")
            val noRoom = e.message?.contains("ENOSPC", ignoreCase = true) == true ||
                e.message?.contains("No space", ignoreCase = true) == true
            transfers.update(
                current.copy(
                    status = TransferStatus.FAILED.name, bytesDone = doneBytes,
                    cause = if (noRoom) TransferCause.NO_ROOM.name else TransferCause.OTHER.name,
                    causeBytes = if (noRoom) StorageCheck.shortfall(store.freeBytes(), current.totalBytes, doneBytes) else null,
                    updatedAtMs = System.currentTimeMillis(),
                ),
            )
            Outcome.Failed
        }
    }

    /** A row whose file, share or server is gone. Fail it rather than retrying forever. */
    private suspend fun abandon(row: TransferEntity, why: String): Outcome {
        Log.w(TAG, "transfer ${row.fileId} abandoned: $why")
        transfers.update(
            row.copy(
                status = TransferStatus.FAILED.name, cause = TransferCause.OTHER.name,
                updatedAtMs = System.currentTimeMillis(),
            ),
        )
        return Outcome.Failed
    }

    /**
     * Cancelled by the app keeps what has been copied and reports success;
     * stopped by the system asks to be run again.
     */
    private fun stopped(): Result =
        if (stopReason == androidx.work.WorkInfo.STOP_REASON_CANCELLED_BY_APP) Result.success() else Result.retry()

    // ── The notification ────────────────────────────────────────────────

    /**
     * WorkManager asks for this BEFORE `doWork` on an expedited request,
     * which is what makes the notification appear on the tap. It already
     * knows the batch size from the rows, so it can say "Keeping 12 videos"
     * before a byte has moved.
     */
    override suspend fun getForegroundInfo(): ForegroundInfo {
        val total = runCatching { transfers.activeCount() }.getOrDefault(0)
        val pendingPicks = runCatching { picks.pendingCount() }.getOrDefault(0)
        val bytes = runCatching { transfers.activeBytes() }.getOrNull()
        return foregroundInfo(
            Progress(
                discovering = pendingPicks > 0,
                total = total,
                bytesTotal = bytes?.total ?: 0,
                bytesDone = bytes?.done ?: 0,
            ),
        )
    }

    /**
     * Push the notification, at most every [NOTIFICATION_INTERVAL_MS]. The
     * row cadence is 500 ms, which is far too fast for the shade and would
     * spend more time drawing than copying.
     */
    private suspend fun notify(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastNotifiedAtMs < NOTIFICATION_INTERVAL_MS) return
        lastNotifiedAtMs = now
        runCatching { setForeground(foregroundInfo(progress)) }
            .onFailure { Log.w(TAG, "no foreground: ${it.message}") }
    }

    private fun foregroundInfo(p: Progress): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(RegolithNotifications.CHANNEL_TRANSFERS, "Downloads", NotificationManager.IMPORTANCE_LOW),
        )
        val title = if (p.total > 1) "Keeping ${p.total} videos on this device" else "Keeping on this device"
        val text = when {
            p.discovering -> QueueProgress.discovering(p.found)
            p.total > 1 -> "${QueueProgress.label(p.done + 1, p.total)} · ${p.name}"
            else -> p.name
        }
        val indeterminate = p.discovering || p.bytesTotal <= 0L
        val notification = NotificationCompat.Builder(applicationContext, RegolithNotifications.CHANNEL_TRANSFERS)
            .setSmallIcon(R.drawable.ic_splash_wordmark)
            .setContentTitle(title)
            .setContentText(text)
            .setProgress(PROGRESS_MAX, QueueProgress.permille(p.bytesDone, p.bytesTotal), indeterminate)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openDownloads())
            .addAction(0, "Stop", WorkManager.getInstance(applicationContext).createCancelPendingIntent(id))
            .build()
        return ForegroundInfo(RegolithNotifications.TRANSFERS_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    /** Tapping the notification goes to Library › On this device, where downloads live. */
    private fun openDownloads(): PendingIntent {
        val intent = Intent(applicationContext, MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_LAUNCHER)
            putExtra(EXTRA_OPEN_DOWNLOADS, true)
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            applicationContext, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        /** Set on the launch intent when the notification is tapped. */
        const val EXTRA_OPEN_DOWNLOADS = "regolith.openDownloads"
        private const val TAG = "Regolith/Transfer"
        private const val BUFFER_SIZE = 1 shl 20
        private const val PROGRESS_INTERVAL_MS = 500L
        private const val NOTIFICATION_INTERVAL_MS = 2_000L

        /** A 1000-step bar visibly moves on a 4 GB file where a 100-step one looks stuck. */
        private const val PROGRESS_MAX = 1000
    }
}
