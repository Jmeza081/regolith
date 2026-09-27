package com.regolith.data.transfer

import android.content.Context
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import com.regolith.data.RegolithNotifications
import com.regolith.data.db.FolderDao
import com.regolith.data.db.ServerDao
import com.regolith.data.db.ShareDao
import com.regolith.data.db.UploadDao
import com.regolith.data.db.UploadEntity
import com.regolith.data.smb.PART_SUFFIX
import com.regolith.domain.media.LocalSource
import com.regolith.domain.smb.ServerAccess
import com.regolith.domain.smb.SmbFailure
import com.regolith.domain.smb.SmbGateway
import com.regolith.domain.transfer.ConflictPolicy
import com.regolith.domain.transfer.PickedFile
import com.regolith.domain.transfer.UploadCause
import com.regolith.domain.transfer.UploadItem
import com.regolith.domain.transfer.UploadNames
import com.regolith.domain.transfer.UploadStatus
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Files going from the phone to a share (P16): what is queued, going,
 * waiting, stuck or done. Screens read rows; [UploadQueueWorker] writes
 * them; [UploadScheduler] runs it. The upload side of [TransferRepository].
 */
@Singleton
class UploadRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val uploads: UploadDao,
    private val folders: FolderDao,
    private val shares: ShareDao,
    private val servers: ServerDao,
    private val access: ServerAccess,
    private val gateway: SmbGateway,
    private val phone: PhoneFiles,
    private val scheduler: UploadScheduler,
) {
    /** Where an upload would go, in words, or why nowhere. */
    data class Destination(val folderId: Long, val folderName: String, val serverId: Long, val serverName: String)

    /** A picked file whose name is already taken in the folder. */
    data class Clash(val file: PickedFile, val existingSize: Long) {
        /** Same name and size: the same file, skipped without asking. */
        val sameFile: Boolean get() = UploadNames.isSameFile(existingSize, file.sizeBytes)
    }

    /** What a pick turned out to be, before anything is queued. */
    data class Prepared(
        val destination: Destination,
        val files: List<PickedFile>,
        /** Picked but not readable at all — nothing can be sent for them. */
        val unreadable: Int,
        val clashes: List<Clash>,
    ) {
        /** Only a clash with a DIFFERENT file needs the user; the same file is simply skipped. */
        val needsAnswer: Boolean get() = clashes.any { !it.sameFile }
    }

    fun observeAll(): Flow<List<UploadItem>> = uploads.observeAll().map { rows -> rows.map { it.toItem() } }

    fun observeFolder(folderId: Long): Flow<List<UploadItem>> = uploads.observeForFolder(folderId).map { rows -> rows.map { it.toItem() } }

    /**
     * The folder as a destination, or null when it cannot be one: phone
     * storage and the demo library have no share behind them to upload to.
     */
    suspend fun destinationOf(folderId: Long): Destination? {
        val folder = folders.byId(folderId) ?: return null
        val share = shares.byId(folder.shareId) ?: return null
        val server = servers.byId(share.serverId) ?: return null
        if (LocalSource.isLocal(server.host)) return null
        return Destination(folder.id, folder.name, server.id, server.name)
    }

    /**
     * Turn what the pickers handed back into files, and find the names
     * already taken — one listing of the folder, so the question about
     * clashes can be asked before anything is sent.
     *
     * Access is made to outlive the app FIRST, while the picker's own grant
     * is still good: by the time the queue reaches a file the app may have
     * been closed, and the grant with it. A listing that fails (the share is
     * out of reach) finds no clashes; the queue settles any it meets later by
     * keeping both, which loses nothing.
     */
    suspend fun prepare(folderId: Long, uris: List<String>): Prepared? {
        val destination = destinationOf(folderId) ?: return null
        val files = mutableListOf<PickedFile>()
        var unreadable = 0
        for (uri in uris.distinct()) {
            phone.keepAccess(uri)
            val file = phone.describe(uri)
            if (file == null) {
                unreadable++
                phone.releaseAccess(uri)
            } else {
                files += file
            }
        }
        val clashes = try {
            val folder = folders.byId(folderId) ?: return null
            val share = shares.byId(folder.shareId) ?: return null
            val existing = gateway.list(access.hostFor(share.serverId), access.credentialsFor(share.serverId), share.name, folder.relPath)
                .filterNot { it.isDirectory }
                .associateBy { it.name.lowercase() }
            files.mapNotNull { file -> existing[file.name.lowercase()]?.let { Clash(file, it.sizeBytes) } }
        } catch (e: SmbFailure) {
            Log.i(TAG, "could not check ${destination.folderName} for clashes: ${e.message}")
            emptyList()
        }
        return Prepared(destination, files, unreadable, clashes)
    }

    /**
     * Queue a pick. [policy] is the answer to the one question asked about
     * clashes with different files; it rides on every row of the batch, so a
     * clash that only appears later (another client, mid-batch) is settled
     * the same way. Returns how many were queued.
     */
    suspend fun enqueue(folderId: Long, files: List<PickedFile>, policy: ConflictPolicy): Int {
        if (files.isEmpty()) return 0
        val now = System.currentTimeMillis()
        uploads.insertAll(
            files.map { file ->
                UploadEntity(
                    batchId = now, folderId = folderId, sourceUri = file.uri, targetName = file.name,
                    sizeBytes = file.sizeBytes, sourceModifiedAtMs = file.modifiedAtMs, conflictPolicy = policy.name,
                    status = UploadStatus.QUEUED.name, cause = null, causeBytes = null, bytesDone = 0,
                    createdAtMs = now, updatedAtMs = now, finishedAtMs = null,
                )
            },
        )
        scheduler.enqueue()
        return files.size
    }

    /** The pick was abandoned at the question: hand back the access [prepare] took for it. */
    suspend fun discard() = releaseUnneeded()

    /** "Try again" on one row. It resumes from its `.part`, so nothing already sent goes again. */
    suspend fun retry(id: Long) {
        uploads.requeue(listOf(id), System.currentTimeMillis())
        dismissAttention()
        scheduler.enqueueNow()
    }

    /** "Try again" over a folder's section: every failure that trying again can fix. */
    suspend fun retryAll(folderId: Long) {
        val ids = uploads.forFolder(folderId).map { it.toItem() }.filter { it.retryable }.map { it.id }
        if (ids.isEmpty()) return
        uploads.requeue(ids, System.currentTimeMillis())
        dismissAttention()
        scheduler.enqueueNow()
    }

    /** "Try now": skip what is left of the wait after the share dropped. */
    suspend fun tryNow() {
        uploads.wakePaused(System.currentTimeMillis())
        scheduler.enqueueNow()
    }

    /**
     * Take one file out of the queue, with whatever of it reached the share.
     *
     * Deleting the row IS the signal to a file in flight: the worker re-reads
     * the row every half second, sees it gone, and deletes the `.part` itself.
     * A file not in flight has nobody watching it, so its `.part` (if an
     * earlier attempt left one) is removed here.
     */
    suspend fun cancel(id: Long) {
        val row = uploads.byId(id) ?: return
        remove(listOf(row))
    }

    /** "Cancel all": everything still owed in this folder. What already arrived stays. */
    suspend fun cancelAll(folderId: Long) {
        remove(uploads.forFolder(folderId).filter { it.toItem().live })
    }

    /** "Clear": the folder's finished rows, and failures the user has given up on. */
    suspend fun clearFinished(folderId: Long) {
        remove(uploads.forFolder(folderId).filterNot { it.toItem().live })
        dismissAttention()
    }

    private suspend fun remove(rows: List<UploadEntity>) {
        if (rows.isEmpty()) return
        uploads.deleteIds(rows.map { it.id })
        rows.filter { it.status != UploadStatus.RUNNING.name && it.status != UploadStatus.DONE.name && it.bytesDone > 0 }
            .forEach { deletePartQuietly(it) }
        releaseUnneeded()
    }

    /**
     * Once per app start, from `AppViewModel`, the way downloads do it.
     *
     *  - Rows a dead process left RUNNING go back in the queue.
     *  - "Just uploaded" rows older than a day go: the section is about now,
     *    and a folder should not greet its visitor with last week's batch.
     *  - Read access nothing needs is handed back. Grants are capped per app,
     *    and a row deleted with its folder never got to release its own.
     */
    suspend fun resumeInterrupted() {
        uploads.requeueRunning()
        val dayAgo = System.currentTimeMillis() - FINISHED_KEPT_MS
        val stale = uploads.all().filter { it.status == UploadStatus.DONE.name && (it.finishedAtMs ?: 0) < dayAgo }
        if (stale.isNotEmpty()) uploads.deleteIds(stale.map { it.id })
        releaseUnneeded()
        if (uploads.activeCount() > 0) scheduler.enqueue()
    }

    private suspend fun releaseUnneeded() {
        val needed = uploads.sourceUris().toSet()
        phone.heldAccess().filterNot { it in needed }.forEach { phone.releaseAccess(it) }
    }

    private suspend fun deletePartQuietly(row: UploadEntity) {
        try {
            val folder = folders.byId(row.folderId) ?: return
            val share = shares.byId(folder.shareId) ?: return
            val path = (if (folder.relPath.isEmpty()) "" else folder.relPath + "/") + row.targetName + PART_SUFFIX
            gateway.delete(access.hostFor(share.serverId), access.credentialsFor(share.serverId), share.name, path)
        } catch (e: SmbFailure) {
            Log.i(TAG, "left ${row.targetName}$PART_SUFFIX on the share: ${e.message}")
        }
    }

    /** The "needs you" notification describes failures that were just retried or cleared; it goes with them. */
    private fun dismissAttention() {
        NotificationManagerCompat.from(context).cancel(RegolithNotifications.UPLOADS_ATTENTION_ID)
    }

    companion object {
        private const val TAG = "Regolith/Upload"
        const val FINISHED_KEPT_MS = 24L * 60 * 60 * 1000
    }
}

/** The row as the rest of the app sees it: statuses as enums, the name it has on the share. */
internal fun UploadEntity.toItem(): UploadItem = UploadItem(
    id = id,
    batchId = batchId,
    folderId = folderId,
    sourceUri = sourceUri,
    name = targetName,
    sizeBytes = sizeBytes,
    bytesDone = bytesDone,
    status = runCatching { UploadStatus.valueOf(status) }.getOrDefault(UploadStatus.FAILED),
    cause = cause?.let { runCatching { UploadCause.valueOf(it) }.getOrNull() },
    causeBytes = causeBytes,
)
