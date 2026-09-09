package com.regolith.data.transfer

import com.regolith.data.db.MediaFileDao
import com.regolith.data.db.TransferDao
import com.regolith.data.db.TransferEntity
import com.regolith.domain.transfer.TransferCause
import com.regolith.domain.transfer.TransferStatus
import kotlinx.coroutines.flow.Flow
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * "On this device": what is kept, what is still arriving, what failed.
 * Screens read rows; the worker writes them; the scheduler runs them.
 */
@Singleton
class TransferRepository @Inject constructor(
    private val transfers: TransferDao,
    private val mediaFileDao: MediaFileDao,
    private val scheduler: TransferScheduler,
    private val store: DownloadStore,
) {
    fun observeAll(): Flow<List<TransferEntity>> = transfers.observeAll()

    fun observeForFile(fileId: Long): Flow<TransferEntity?> = transfers.observeForFile(fileId)

    /** "Keep on this device": create the row (or resume a paused / failed one) and run it. */
    suspend fun start(fileId: Long) {
        val file = mediaFileDao.byId(fileId) ?: return
        val existing = transfers.byFile(fileId)
        val now = System.currentTimeMillis()
        if (existing == null) {
            transfers.insert(
                TransferEntity(
                    fileId = fileId, status = TransferStatus.QUEUED.name, bytesDone = 0, totalBytes = file.sizeBytes,
                    localPath = store.relPathFor(fileId, file.ext), cause = null, causeBytes = null,
                    createdAtMs = now, updatedAtMs = now, finishedAtMs = null,
                ),
            )
        } else if (existing.status == TransferStatus.DONE.name) {
            return
        } else {
            transfers.update(existing.copy(status = TransferStatus.QUEUED.name, cause = null, causeBytes = null, updatedAtMs = now))
        }
        scheduler.enqueue(fileId)
    }

    /** Stop a transfer and keep nothing. */
    suspend fun cancel(fileId: Long) {
        scheduler.cancel(fileId)
        transfers.byFile(fileId)?.let { store.delete(it.localPath) }
        transfers.deleteForFile(fileId)
    }

    /** "Remove from this device": the copy goes, the share is untouched. */
    suspend fun remove(fileId: Long) = cancel(fileId)

    /** "Clear all" under Failed. */
    suspend fun clearFailed() {
        transfers.allWith(TransferStatus.FAILED.name).forEach { store.delete(it.localPath) }
        transfers.deleteFailed()
    }

    /** The finished copy, or null. Blocking: also used from ExoPlayer's loader thread. */
    fun localFileBlocking(fileId: Long): File? {
        val row = transfers.doneForFileBlocking(fileId) ?: return null
        return store.fileFor(row.localPath).takeIf { it.exists() }
    }

    suspend fun localFile(fileId: Long): File? {
        val row = transfers.doneForFile(fileId) ?: return null
        return store.fileFor(row.localPath).takeIf { it.exists() }
    }

    data class Storage(val usedBytes: Long, val freeBytes: Long, val totalBytes: Long)

    /** "7.8 of 32 GB": what the copies take, and what the phone has. */
    fun storage(): Storage = Storage(store.usedBytes(), store.freeBytes(), store.totalBytes())

    companion object {
        fun TransferEntity.statusEnum(): TransferStatus = runCatching { TransferStatus.valueOf(status) }.getOrDefault(TransferStatus.FAILED)
        fun TransferEntity.causeEnum(): TransferCause? = cause?.let { runCatching { TransferCause.valueOf(it) }.getOrNull() }
    }
}
