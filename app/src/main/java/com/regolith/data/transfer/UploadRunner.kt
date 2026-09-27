package com.regolith.data.transfer

import android.util.Log
import com.regolith.data.db.FolderDao
import com.regolith.data.db.ServerDao
import com.regolith.data.db.ShareDao
import com.regolith.data.db.UploadDao
import com.regolith.data.db.UploadEntity
import com.regolith.data.smb.PART_SUFFIX
import com.regolith.domain.media.LocalSource
import com.regolith.domain.media.MediaFileTypes
import com.regolith.domain.smb.ServerAccess
import com.regolith.domain.smb.SmbCredentials
import com.regolith.domain.smb.SmbEntry
import com.regolith.domain.smb.SmbFailure
import com.regolith.domain.smb.SmbGateway
import com.regolith.domain.smb.SmbHost
import com.regolith.domain.transfer.ConflictPolicy
import com.regolith.domain.transfer.UploadCause
import com.regolith.domain.transfer.UploadNames
import com.regolith.domain.transfer.UploadStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import javax.inject.Inject

/**
 * Sends ONE queued file to its folder on the share: the upload queue's
 * engine, kept apart from the worker that schedules it so that every way an
 * upload can end is tested against a fake share instead of a real NAS.
 *
 * The order of things is the design:
 *  1. ONE listing of the folder answers both questions a send needs — is the
 *     name free, and did an earlier attempt leave a `.part` behind.
 *  2. A clash is settled by the row's policy (the user was asked once, for
 *     the whole pick), except that the same name AND size is the same file,
 *     which is skipped without asking.
 *  3. The share's free space is checked against what is left to send, so a
 *     full share fails in a second instead of after 600 MB.
 *  4. The bytes are appended to `<name>.part` from the `.part`'s own end: a
 *     retry never resends what arrived before the network dropped.
 *  5. Only a whole file is renamed to its real name, and never over anything
 *     unless the user chose Replace. Nobody ever sees half a photo.
 */
class UploadRunner @Inject constructor(
    private val uploads: UploadDao,
    private val folders: FolderDao,
    private val shares: ShareDao,
    private val servers: ServerDao,
    private val access: ServerAccess,
    private val gateway: SmbGateway,
    private val phone: PhoneFiles,
) {
    /** How one file ended, as far as the queue needs to know. */
    sealed interface Outcome {
        /** On the share. [video] asks for the folder to be listed again, so the film appears in Browse. */
        data class Done(val folderId: Long, val video: Boolean) : Outcome

        /** Deliberately not sent: already there, or the user chose to skip the clash. */
        data object Skipped : Outcome

        /** This file did not make it, and its row says why. The rest of the queue carries on. */
        data object Failed : Outcome

        /** The server went quiet. The whole queue waits, and tries again later. */
        data object Paused : Outcome

        /** Taken out of the queue while it was going. */
        data object Cancelled : Outcome
    }

    /** How often the row is written, and re-read for a Cancel. Settable so a test need not wait. */
    internal var progressIntervalMs: Long = PROGRESS_INTERVAL_MS

    private data class Place(val host: SmbHost, val creds: SmbCredentials, val share: String, val dir: String, val serverId: Long)

    /** The row changed under a file in flight: deleted (Cancel) or no longer RUNNING (Stop). */
    private class Interrupted(val deleted: Boolean) : Exception()

    /** The `.part` and the phone's file no longer agree; the whole file is sent again, once. */
    private class StartOver : Exception()

    /** Send [row]. [onProgress] hears each write of the row, for the notification. */
    suspend fun send(row: UploadEntity, onProgress: suspend (UploadEntity) -> Unit = {}): Outcome {
        val folder = folders.byId(row.folderId) ?: return fail(row, UploadCause.FOLDER_GONE)
        val share = shares.byId(folder.shareId) ?: return fail(row, UploadCause.FOLDER_GONE)
        val server = servers.byId(share.serverId) ?: return fail(row, UploadCause.FOLDER_GONE)
        // Phone storage and the demo library have no share behind them. Browse
        // never offers to upload there; this is the backstop.
        if (LocalSource.isLocal(server.host)) return fail(row, UploadCause.OTHER)

        val running = row.copy(status = UploadStatus.RUNNING.name, cause = null, causeBytes = null, updatedAtMs = now())
        uploads.update(running)
        onProgress(running)
        var place: Place? = null
        return try {
            val at = Place(access.hostFor(server.id), access.credentialsFor(server.id), share.name, folder.relPath, server.id)
            place = at
            transfer(running, at, onProgress)
        } catch (e: CancellationException) {
            // Stopped by WorkManager, not by anyone in the app: back in the
            // queue with its .part kept, so the next run carries on from it.
            // NonCancellable, because a cancelled coroutine cannot even ask
            // Room a question otherwise.
            withContext(NonCancellable) {
                uploads.byId(row.id)?.takeIf { it.status == UploadStatus.RUNNING.name }?.let {
                    uploads.update(it.copy(status = UploadStatus.QUEUED.name, updatedAtMs = now()))
                }
            }
            throw e
        } catch (e: SmbFailure) {
            failure(running, e, place)
        } catch (e: IOException) {
            Log.w(TAG, "${row.targetName} failed", e)
            fail(running, UploadCause.OTHER)
        }
    }

    private suspend fun transfer(row: UploadEntity, at: Place, onProgress: suspend (UploadEntity) -> Unit): Outcome {
        // 1. One listing: is the name free, and is there a .part to carry on from.
        val entries = gateway.list(at.host, at.creds, at.share, at.dir)
        servers.markReachable(at.serverId, now())
        val byName = entries.associateBy { it.name.lowercase() }
        val names = entries.mapTo(HashSet()) { it.name }

        // 2. The name it will have.
        val policy = runCatching { ConflictPolicy.valueOf(row.conflictPolicy) }.getOrDefault(ConflictPolicy.KEEP_BOTH)
        var target = row.targetName
        var replace = false
        val clash = byName[target.lowercase()]
        if (clash != null) {
            when {
                // A FOLDER has the name: never replaced, whatever the policy said.
                clash.isDirectory -> target = UploadNames.keepBoth(target, names)
                UploadNames.isSameFile(clash.sizeBytes, row.sizeBytes) -> return skip(row, UploadCause.ALREADY_THERE, at, byName)
                policy == ConflictPolicy.SKIP -> return skip(row, UploadCause.SKIPPED, at, byName)
                policy == ConflictPolicy.REPLACE -> replace = true
                else -> target = UploadNames.keepBoth(target, names)
            }
        }
        var current = row
        if (target != row.targetName) {
            // Settled for good before a byte is written: the .part is named
            // after it, and the next attempt has to find that .part again.
            current = row.copy(targetName = target, updatedAtMs = now())
            uploads.update(current)
        }
        val partName = target + PART_SUFFIX
        val partPath = join(at.dir, partName)

        try {
            // 3. Room for what is left to send. A .part bigger than the file
            // belongs to some other upload of that name; it cannot be resumed.
            var partSize = byName[partName.lowercase()]?.takeIf { !it.isDirectory }?.sizeBytes ?: 0L
            if (row.sizeBytes >= 0 && partSize > row.sizeBytes) {
                gateway.delete(at.host, at.creds, at.share, partPath)
                partSize = 0
            }
            val left = if (row.sizeBytes >= 0) row.sizeBytes - partSize else null
            if (left != null && left > 0) {
                val free = gateway.freeBytes(at.host, at.creds, at.share)
                if (free != null && left > free) return fail(current, UploadCause.SHARE_FULL, causeBytes = left - free)
            }

            // 4. The bytes, into the .part, from the .part's own end.
            var sent = -1L
            var startedOver = false
            while (sent < 0) {
                try {
                    sent = stream(current, at, partPath, onProgress)
                } catch (e: StartOver) {
                    if (startedOver) throw IOException("${row.targetName} kept changing while it was being sent")
                    startedOver = true
                    Log.i(TAG, "${row.targetName} changed on the phone; sending it again from the start")
                    gateway.delete(at.host, at.creds, at.share, partPath)
                    // An edited photo has a new size. Without taking it again
                    // the second pass would disagree with the file the same way.
                    phone.describe(current.sourceUri)?.let { fresh ->
                        current = current.copy(sizeBytes = fresh.sizeBytes, updatedAtMs = now())
                        uploads.update(current)
                    }
                }
            }

            // 5. The real name, now that the file is whole.
            val landed = land(at, partPath, target, replace)
            current.sourceModifiedAtMs?.let { ms ->
                try {
                    gateway.setModifiedTime(at.host, at.creds, at.share, join(at.dir, landed), ms)
                } catch (e: SmbFailure) {
                    Log.i(TAG, "$landed keeps its upload time: ${e.message}")
                }
            }
            // A row deleted in the last half second: the file arrived anyway,
            // so it is reported as landed, and there is simply nothing to mark.
            uploads.byId(row.id)?.let { latest ->
                val t = now()
                uploads.update(
                    latest.copy(
                        status = UploadStatus.DONE.name, cause = null, causeBytes = null, targetName = landed,
                        bytesDone = sent, sizeBytes = maxOf(latest.sizeBytes, sent), updatedAtMs = t, finishedAtMs = t,
                    ),
                )
            }
            return Outcome.Done(row.folderId, video = MediaFileTypes.isVideo(landed))
        } catch (e: Interrupted) {
            // Cancelled from the app: nobody will resume it, so the half-file goes too.
            if (e.deleted) deleteQuietly(at, partPath)
            return Outcome.Cancelled
        } catch (e: SourceGone) {
            Log.w(TAG, "${row.targetName} is no longer readable on the phone", e)
            deleteQuietly(at, partPath)
            return fail(current, UploadCause.SOURCE_GONE)
        }
    }

    /**
     * Append the phone's file to [partPath] from wherever the `.part` ends.
     * Returns what the `.part` holds once the file is whole; throws
     * [StartOver] when the two disagree about where that is.
     */
    private suspend fun stream(row: UploadEntity, at: Place, partPath: String, onProgress: suspend (UploadEntity) -> Unit): Long {
        val buffer = ByteArray(BUFFER_SIZE)
        gateway.openForAppend(at.host, at.creds, at.share, partPath).use { sink ->
            val start = sink.startOffset
            if (row.sizeBytes >= 0 && start > row.sizeBytes) throw StartOver()
            val source = try {
                phone.open(row.sourceUri, start)
            } catch (e: SourceChanged) {
                throw StartOver()
            }
            source.use { input ->
                var done = start
                var current = row.copy(bytesDone = done, updatedAtMs = now())
                uploads.update(current)
                onProgress(current)
                var lastTick = now()
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val n = fill(input, buffer, row.sourceUri)
                    if (n <= 0) break
                    sink.write(buffer, 0, n)
                    done += n
                    val t = now()
                    if (t - lastTick >= progressIntervalMs) {
                        lastTick = t
                        // The row is the app's only handle on a file in flight:
                        // Cancel deletes it, Stop marks it, and either is seen here.
                        val live = uploads.byId(row.id) ?: throw Interrupted(deleted = true)
                        if (live.status != UploadStatus.RUNNING.name) throw Interrupted(deleted = false)
                        current = live.copy(bytesDone = done, updatedAtMs = t)
                        uploads.update(current)
                        onProgress(current)
                    }
                }
                // Shorter than the phone said at the start: it changed under us,
                // and the .part is now two different files end to end.
                if (row.sizeBytes >= 0 && done < row.sizeBytes) throw StartOver()
                return done
            }
        }
    }

    /**
     * Fill [buffer] as far as the stream allows, so the share gets one
     * write per MiB rather than one per whatever `read` happened to return.
     */
    private fun fill(input: InputStream, buffer: ByteArray, uri: String): Int {
        var total = 0
        while (total < buffer.size) {
            val n = try {
                input.read(buffer, total, buffer.size - total)
            } catch (e: IOException) {
                throw SourceGone(uri, e)
            }
            if (n < 0) break
            total += n
        }
        return total
    }

    /**
     * Rename the finished `.part` to its real name. Non-replacing unless the
     * user chose Replace, so a name another client took while this file was
     * on its way is never destroyed: the upload keeps both instead.
     */
    private suspend fun land(at: Place, partPath: String, target: String, replace: Boolean): String {
        try {
            gateway.rename(at.host, at.creds, at.share, partPath, join(at.dir, target), replace = replace)
            return target
        } catch (e: SmbFailure.Other) {
            if (replace) {
                // Some servers will not rename onto a name that exists even when
                // told to; writeReplacing's answer holds here too.
                gateway.delete(at.host, at.creds, at.share, join(at.dir, target))
                gateway.rename(at.host, at.creds, at.share, partPath, join(at.dir, target), replace = true)
                return target
            }
            val taken = gateway.list(at.host, at.creds, at.share, at.dir).mapTo(HashSet()) { it.name }
            if (taken.none { it.equals(target, ignoreCase = true) }) throw e
            val other = UploadNames.keepBoth(target, taken)
            Log.i(TAG, "$target was taken while it was on its way; it lands as $other")
            gateway.rename(at.host, at.creds, at.share, partPath, join(at.dir, other), replace = false)
            return other
        }
    }

    private suspend fun skip(row: UploadEntity, cause: UploadCause, at: Place, byName: Map<String, SmbEntry>): Outcome {
        // An earlier attempt's .part is not wanted any more.
        val part = row.targetName + PART_SUFFIX
        if (byName.containsKey(part.lowercase())) deleteQuietly(at, join(at.dir, part))
        val t = now()
        uploads.update(row.copy(status = UploadStatus.DONE.name, cause = cause.name, causeBytes = null, updatedAtMs = t, finishedAtMs = t))
        return Outcome.Skipped
    }

    /**
     * What an SMB failure means for this file and the ones waiting behind it.
     * The server gone is a pause for everything; a refusal is said once for
     * the folder (or the server) it is about; a full share fails this file
     * only, since a smaller one may still fit.
     */
    private suspend fun failure(row: UploadEntity, e: SmbFailure, at: Place?): Outcome {
        val latest = uploads.byId(row.id) ?: return Outcome.Cancelled
        val t = now()
        return when (e) {
            is SmbFailure.Unreachable -> {
                Log.w(TAG, "${row.targetName} paused at ${latest.bytesDone}: ${e.message}")
                at?.let { servers.markUnreachable(it.serverId, t) }
                uploads.update(latest.copy(status = UploadStatus.PAUSED.name, cause = UploadCause.SHARE_DROPPED.name, updatedAtMs = t))
                Outcome.Paused
            }
            is SmbFailure.AuthFailed -> {
                val result = fail(latest, UploadCause.SIGN_IN)
                at?.let { uploads.failWaitingOnServer(it.serverId, UploadCause.SIGN_IN.name, t) }
                result
            }
            is SmbFailure.Forbidden -> {
                val result = fail(latest, UploadCause.READ_ONLY)
                uploads.failWaitingInFolder(row.folderId, UploadCause.READ_ONLY.name, t)
                result
            }
            is SmbFailure.NotFound -> {
                // Either the folder has gone, or something inside it went
                // missing mid-send (the .part, deleted by someone else). Only
                // the first is final, and one listing tells them apart.
                val folderThere = at != null && runCatching { gateway.list(at.host, at.creds, at.share, at.dir) }.isSuccess
                if (folderThere) {
                    fail(latest, UploadCause.OTHER)
                } else {
                    val result = fail(latest, UploadCause.FOLDER_GONE)
                    uploads.failWaitingInFolder(row.folderId, UploadCause.FOLDER_GONE.name, t)
                    result
                }
            }
            is SmbFailure.ShareFull -> {
                val need = if (latest.sizeBytes >= 0) (latest.sizeBytes - latest.bytesDone).coerceAtLeast(0) else null
                fail(latest, UploadCause.SHARE_FULL, causeBytes = need)
            }
            is SmbFailure.Other -> {
                Log.w(TAG, "${row.targetName} failed: ${e.detail ?: e.message}")
                fail(latest, UploadCause.OTHER)
            }
        }
    }

    private suspend fun fail(row: UploadEntity, cause: UploadCause, causeBytes: Long? = null): Outcome {
        val latest = uploads.byId(row.id) ?: return Outcome.Cancelled
        uploads.update(latest.copy(status = UploadStatus.FAILED.name, cause = cause.name, causeBytes = causeBytes, updatedAtMs = now()))
        return Outcome.Failed
    }

    /** Best effort: a stray .part is untidy, not wrong, and a failed cleanup must not mask the real outcome. */
    private suspend fun deleteQuietly(at: Place, partPath: String) {
        withContext(NonCancellable) {
            try {
                gateway.delete(at.host, at.creds, at.share, partPath)
            } catch (e: SmbFailure) {
                Log.i(TAG, "left $partPath on the share: ${e.message}")
            }
        }
    }

    private fun join(dir: String, name: String) = if (dir.isEmpty()) name else "$dir/$name"

    private fun now() = System.currentTimeMillis()

    companion object {
        private const val TAG = "Regolith/Upload"

        /** One SMB write per MiB, the size the download queue reads in. */
        const val BUFFER_SIZE = 1 shl 20

        /** How often a file in flight writes its row: the same beat as the download queue. */
        const val PROGRESS_INTERVAL_MS = 500L
    }
}
