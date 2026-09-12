package com.regolith.data.transfer

import com.regolith.data.db.DownloadPickDao
import com.regolith.data.db.DownloadPickEntity
import com.regolith.data.db.MediaFileDao
import com.regolith.data.db.TransferDao
import com.regolith.data.db.TransferEntity
import com.regolith.domain.transfer.FolderExclusions
import com.regolith.domain.transfer.FolderPick
import com.regolith.domain.transfer.TransferCause
import com.regolith.domain.transfer.TransferStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
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
    private val picks: DownloadPickDao,
    private val mediaFileDao: MediaFileDao,
    private val scheduler: TransferScheduler,
    private val store: DownloadStore,
) {
    fun observeAll(): Flow<List<TransferEntity>> = transfers.observeAll()

    fun observeForFile(fileId: Long): Flow<TransferEntity?> = transfers.observeForFile(fileId)

    /** Rows still owed — queued, copying or paused. Drives the Settings row and the nav dot. */
    fun observeActive(): Flow<List<TransferEntity>> = transfers.observeAll().map { rows ->
        rows.filter { it.statusEnum() in ACTIVE_STATUSES }
    }

    /** File ids already on the device, so a selection can report "3 already here". */
    fun observeDoneFileIds(): Flow<Set<Long>> = transfers.observeDoneFileIds().map { it.toSet() }

    /** Folders still waiting to be walked, so the UI can say the count is still moving. */
    fun observePendingPicks(): Flow<Int> = picks.observePendingCount()

    /**
     * Queue a batch of files picked directly. Returns how many rows were
     * added — a file already downloaded or already queued is skipped, which
     * is what makes tapping Download twice harmless.
     */
    suspend fun startAll(fileIds: Collection<Long>): Int {
        var added = 0
        val now = System.currentTimeMillis()
        for (id in fileIds.distinct()) {
            val file = mediaFileDao.byId(id) ?: continue
            val existing = transfers.byFile(id)
            when {
                existing == null -> {
                    transfers.insert(
                        TransferEntity(
                            fileId = id, status = TransferStatus.QUEUED.name, bytesDone = 0, totalBytes = file.sizeBytes,
                            localPath = store.relPathFor(id, file.ext), cause = null, causeBytes = null,
                            createdAtMs = now, updatedAtMs = now, finishedAtMs = null,
                        ),
                    )
                    added++
                }
                existing.status == TransferStatus.DONE.name -> Unit
                existing.status == TransferStatus.RUNNING.name -> Unit
                else -> {
                    // A failed or paused row is retried rather than duplicated.
                    transfers.update(existing.copy(status = TransferStatus.QUEUED.name, cause = null, causeBytes = null, updatedAtMs = now))
                    added++
                }
            }
        }
        if (added > 0) scheduler.enqueue()
        return added
    }

    /**
     * Queue whole folders. The files inside them are not known yet — a
     * folder the user never opened has no rows — so each pick becomes a
     * `download_picks` job for the queue worker to walk over SMB.
     */
    suspend fun addFolderPicks(folderPicks: Map<FolderPick, FolderExclusions>) {
        if (folderPicks.isEmpty()) return
        val now = System.currentTimeMillis()
        folderPicks.forEach { (pick, out) ->
            picks.insert(
                DownloadPickEntity(
                    folderId = pick.folderId, shareId = pick.shareId, relPath = pick.relPath,
                    discovered = false, filesFound = 0, createdAtMs = now,
                    excludedFileIds = out.fileIds.joinToString(","),
                    excludedPaths = out.paths.joinToString("\n"),
                ),
            )
        }
        scheduler.enqueue()
    }

    /**
     * "Stop" on the notification or in Settings.
     *
     * Rows become FAILED · CANCELLED rather than being deleted, so a
     * stopped batch lands in the design's "Failed · N" section with its
     * "Try again" instead of silently vanishing, and the bytes already
     * copied stay on disk for the resume. This is the only place
     * [TransferCause.CANCELLED] is written.
     */
    suspend fun cancelAll() {
        picks.clear()
        transfers.cancelActive(System.currentTimeMillis())
        scheduler.cancelAll()
    }

    /**
     * Rows a dead process left RUNNING, put back in the queue at startup.
     *
     * Called once from `AppViewModel`. Without it a kill mid-copy left rows
     * reading RUNNING forever, with no worker behind them.
     */
    suspend fun resumeInterrupted() {
        transfers.requeueRunning()
        if (transfers.activeCount() > 0 || picks.pendingCount() > 0) scheduler.enqueue()
    }

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
        scheduler.enqueue()
    }

    /**
     * Stop one transfer and keep nothing.
     *
     * No longer cancels a worker: there is one queue for every file, so
     * killing it would stop the other eleven downloads too. Deleting the
     * row IS the signal — the copy loop re-reads it every 500 ms and gives
     * up on a file that has gone.
     */
    suspend fun cancel(fileId: Long) {
        transfers.byFile(fileId)?.let { store.delete(it.localPath) }
        transfers.deleteForFile(fileId)
    }

    /** "Remove from this device": the copy goes, the share is untouched. */
    suspend fun remove(fileId: Long) = cancel(fileId)

    /**
     * Remove several copies at once: the On-this-device page's multi-delete.
     *
     * The share is untouched — this only deletes what was copied here, and
     * the row with it, so the title goes back to streaming.
     */
    suspend fun removeAll(fileIds: Collection<Long>) {
        fileIds.distinct().forEach { cancel(it) }
    }

    /**
     * "Clear all" on the On-this-device page: nothing stays on the phone.
     *
     * Takes the arriving ones with it, which is the honest reading of a page
     * that lists them together — a Clear all that left a download running
     * would be refilling the page it just emptied. Stops the queue first so
     * the worker is not mid-write into a file being deleted.
     */
    suspend fun removeEverything() {
        picks.clear()
        scheduler.cancelAll()
        val all = transfers.observeAll().first()
        all.forEach { store.delete(it.localPath) }
        all.forEach { transfers.deleteForFile(it.fileId) }
    }

    /** "Clear failed": the rows that gave up, and whatever they had copied. */
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
        /** Still owed: what the dot and the Settings row count. */
        val ACTIVE_STATUSES = setOf(TransferStatus.QUEUED, TransferStatus.RUNNING, TransferStatus.PAUSED)

        fun TransferEntity.statusEnum(): TransferStatus = runCatching { TransferStatus.valueOf(status) }.getOrDefault(TransferStatus.FAILED)
        fun TransferEntity.causeEnum(): TransferCause? = cause?.let { runCatching { TransferCause.valueOf(it) }.getOrNull() }
    }
}
