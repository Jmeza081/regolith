package com.regolith.data.transfer

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
import com.regolith.data.db.MediaFileDao
import com.regolith.data.db.ServerDao
import com.regolith.data.db.ShareDao
import com.regolith.data.db.TransferDao
import com.regolith.data.db.TransferEntity
import com.regolith.data.repository.SourceRepository
import com.regolith.domain.smb.SmbFailure
import com.regolith.domain.smb.SmbGateway
import com.regolith.domain.smb.SmbHost
import com.regolith.domain.transfer.StorageCheck
import com.regolith.domain.transfer.TransferCause
import com.regolith.domain.transfer.TransferStatus
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.RandomAccessFile

/**
 * Copies one file off the share into the downloads directory, resuming
 * from `transfers.bytesDone` if it has run before. The share dropping out
 * pauses the transfer and asks WorkManager to retry (with backoff, when
 * the network is back): "resumes on its own". No room fails it for good
 * with the shortfall in the row, because that needs the user.
 */
@HiltWorker
class TransferWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val transfers: TransferDao,
    private val mediaFileDao: MediaFileDao,
    private val shareDao: ShareDao,
    private val serverDao: ServerDao,
    private val sources: SourceRepository,
    private val gateway: SmbGateway,
    private val store: DownloadStore,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val fileId = inputData.getLong(KEY_FILE_ID, -1)
        val row = transfers.byFile(fileId) ?: return@withContext Result.failure()
        if (row.status == TransferStatus.DONE.name) return@withContext Result.success()
        val file = mediaFileDao.byId(fileId) ?: return@withContext Result.failure()
        val share = shareDao.byId(file.shareId) ?: return@withContext Result.failure()
        val server = serverDao.byId(share.serverId) ?: return@withContext Result.failure()
        runCatching { setForeground(foregroundInfo(file.name)) }.onFailure { Log.w(TAG, "no foreground: ${it.message}") }

        val part = store.partFor(row.localPath)
        part.parentFile?.mkdirs()
        // Trust the file on disk over the row: a crash between a write and
        // the row update leaves the file slightly ahead, never behind.
        var done = if (part.exists()) part.length() else 0L
        var current = row.copy(status = TransferStatus.RUNNING.name, bytesDone = done, cause = null, causeBytes = null, updatedAtMs = System.currentTimeMillis())
        transfers.update(current)

        if (!StorageCheck.hasRoom(store.freeBytes(), row.totalBytes, done)) {
            val need = StorageCheck.shortfall(store.freeBytes(), row.totalBytes, done)
            transfers.update(current.copy(status = TransferStatus.FAILED.name, cause = TransferCause.NO_ROOM.name, causeBytes = need, updatedAtMs = System.currentTimeMillis()))
            return@withContext Result.failure()
        }

        try {
            val host = SmbHost(server.host, server.port)
            val credentials = sources.credentialsFor(server.id)
            gateway.open(host, credentials, share.name, file.relPath).use { src ->
                sources.markReachable(server.id)
                val total = src.size
                if (total != current.totalBytes) current = current.copy(totalBytes = total)
                RandomAccessFile(part, "rw").use { out ->
                    out.seek(done)
                    val buffer = ByteArray(BUFFER_SIZE)
                    var lastWrite = 0L
                    while (done < total) {
                        ensureActive()
                        val n = src.readAt(done, buffer, 0, minOf(buffer.size.toLong(), total - done).toInt())
                        if (n <= 0) break
                        out.write(buffer, 0, n)
                        done += n
                        val now = System.currentTimeMillis()
                        if (now - lastWrite > PROGRESS_INTERVAL_MS) {
                            lastWrite = now
                            current = current.copy(bytesDone = done, updatedAtMs = now)
                            transfers.update(current)
                        }
                    }
                }
                if (done < total) throw SmbFailure.Other("The share returned fewer bytes than the file has")
            }
            if (!part.renameTo(store.fileFor(row.localPath))) throw java.io.IOException("Could not finish ${row.localPath}")
            transfers.update(current.copy(status = TransferStatus.DONE.name, bytesDone = done, updatedAtMs = System.currentTimeMillis(), finishedAtMs = System.currentTimeMillis()))
            Result.success()
        } catch (e: CancellationException) {
            // Stopped by WorkManager (the user cancelled, or the system reclaimed us). The row is
            // already CANCELLED by the repository in the first case; keep the bytes for a resume.
            transfers.byFile(fileId)?.let { if (it.status == TransferStatus.RUNNING.name) transfers.update(it.copy(status = TransferStatus.QUEUED.name, bytesDone = done, updatedAtMs = System.currentTimeMillis())) }
            throw e
        } catch (e: SmbFailure) {
            Log.w(TAG, "transfer $fileId paused at $done: ${e.message}")
            sources.markUnreachable(server.id)
            transfers.update(current.copy(status = TransferStatus.PAUSED.name, bytesDone = done, cause = TransferCause.SHARE_DROPPED.name, updatedAtMs = System.currentTimeMillis()))
            Result.retry()
        } catch (e: java.io.IOException) {
            Log.w(TAG, "transfer $fileId failed at $done: $e")
            val noRoom = e.message?.contains("ENOSPC", ignoreCase = true) == true || e.message?.contains("No space", ignoreCase = true) == true
            transfers.update(
                current.copy(
                    status = TransferStatus.FAILED.name, bytesDone = done,
                    cause = if (noRoom) TransferCause.NO_ROOM.name else TransferCause.OTHER.name,
                    causeBytes = if (noRoom) StorageCheck.shortfall(store.freeBytes(), current.totalBytes, done) else null,
                    updatedAtMs = System.currentTimeMillis(),
                ),
            )
            Result.failure()
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo("Downloading")

    private fun foregroundInfo(name: String): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Downloads", NotificationManager.IMPORTANCE_LOW))
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_splash_wordmark)
            .setContentTitle("Keeping on this device")
            .setContentText(name)
            .setOngoing(true)
            .build()
        return ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    companion object {
        const val KEY_FILE_ID = "fileId"
        private const val TAG = "Regolith/Transfer"
        private const val CHANNEL_ID = "transfers"
        private const val NOTIFICATION_ID = 42
        private const val BUFFER_SIZE = 1 shl 20
        private const val PROGRESS_INTERVAL_MS = 500L
    }
}
