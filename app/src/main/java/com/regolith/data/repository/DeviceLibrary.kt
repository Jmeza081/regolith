package com.regolith.data.repository

import android.util.Log
import com.regolith.data.db.FolderDao
import com.regolith.data.db.FolderEntity
import com.regolith.data.db.MediaFileDao
import com.regolith.data.db.ServerDao
import com.regolith.data.db.ServerEntity
import com.regolith.data.db.ShareDao
import com.regolith.data.db.ShareEntity
import com.regolith.data.db.TransferDao
import com.regolith.data.transfer.DownloadStore
import com.regolith.domain.media.DeviceSource
import com.regolith.domain.model.AuthMode
import javax.inject.Inject
import javax.inject.Singleton

/**
 * "On this device" as a library source of its own ([DeviceSource]).
 *
 * A downloaded copy stops belonging to the share the moment the share
 * stops being connected. Until then nothing here runs: while the server is
 * there, a film is one row with a copy on disk, which is what keeps Title
 * Detail's "On this device" check honest and what stops the same film
 * appearing twice on the Library wall.
 *
 * Web analogy: re-parenting a record from one collection to another,
 * primary key untouched, instead of deleting and re-creating it.
 */
@Singleton
class DeviceLibrary @Inject constructor(
    private val serverDao: ServerDao,
    private val shareDao: ShareDao,
    private val folderDao: FolderDao,
    private val mediaFileDao: MediaFileDao,
    private val transferDao: TransferDao,
    private val downloads: DownloadStore,
) {
    /** What a disconnect did with the copies it found: how many stayed, how many could not. */
    data class Adopted(val kept: Int, val discarded: Int)

    /**
     * Move every finished copy under [serverId] into the device source, and
     * throw away the half-finished ones.
     *
     * Called from [SourceRepository.removeServer] BEFORE the cascade, which
     * is the whole trick: a row that has been re-pointed at the device
     * share is no longer a descendant of the server being deleted, so the
     * cascade cannot reach it. Its id does not change, so `transfers`,
     * `playback_progress`, `user_chapters` and `artwork` all keep pointing
     * at the same film without being touched.
     *
     * An UNFINISHED transfer is not adopted. A half-copied file whose share
     * has gone can never be resumed, so keeping it would mean a permanently
     * stuck row over bytes that will never be a video.
     */
    suspend fun adopt(serverId: Long): Adopted {
        val files = mediaFileDao.downloadedUnderServer(serverId)
        val finishedIds = files.map { it.id }.toSet()

        // The unfinished ones: their rows are about to cascade away, and the
        // bytes are ours to remove — the same duty `FileOpsRepository`
        // discharges when a delete takes a file row out from under a copy.
        var discarded = 0
        for (row in mediaFileDao.transfersUnderServer(serverId)) {
            if (row.fileId in finishedIds) continue
            downloads.delete(row.localPath)
            discarded++
        }
        if (files.isEmpty()) return Adopted(kept = 0, discarded = discarded)

        val root = deviceRootFolder()
        var kept = 0
        for (file in files) {
            val transfer = transferDao.byFile(file.id) ?: continue
            // A row can say DONE while the bytes are gone — the user cleared
            // the app's storage, say. There is nothing to adopt, and letting
            // it cascade is correct; the sweep tidies the row's leftovers.
            if (!downloads.fileFor(transfer.localPath).exists()) continue
            mediaFileDao.update(
                file.copy(
                    shareId = root.shareId,
                    folderId = root.id,
                    // Inside the device source a file's path IS where the
                    // bytes are, which `DownloadStore` already decided. The
                    // film's own filename stays in `name`, and its parsed
                    // title, year, season and episode are untouched — so the
                    // tile reads exactly as it did an instant ago.
                    relPath = transfer.localPath,
                    // It was only ever "missing" in the sense of "not on the
                    // share". It is on the phone, which is now the point.
                    missing = false,
                ),
            )
            kept++
        }
        Log.i(TAG, "server $serverId disconnected: kept $kept, discarded $discarded")
        return Adopted(kept = kept, discarded = discarded)
    }

    /** How many copies a disconnect would keep, for the sentence the dialog promises. */
    suspend fun downloadedCount(serverId: Long): Int = mediaFileDao.downloadedCountUnderServer(serverId)

    /** The device share's id, or null when nothing has ever been adopted. */
    suspend fun shareId(): Long? {
        val server = serverDao.byHost(DeviceSource.HOST, PORT) ?: return null
        return shareDao.byName(server.id, DeviceSource.SHARE)?.id
    }

    /**
     * Take the device source away once it holds nothing.
     *
     * An empty source is worse than no source: it puts a "This device"
     * server in Settings and an empty shelf on the Library wall for someone
     * who has removed every copy they ever kept. Created on first adoption,
     * removed on the sweep after the last removal.
     */
    suspend fun removeIfEmpty() {
        val server = serverDao.byHost(DeviceSource.HOST, PORT) ?: return
        // Both shares, not just Downloads: phone storage lives on this server
        // too, and taking it away would cascade every resume point on a
        // phone video with it.
        for (name in listOf(DeviceSource.SHARE, DeviceSource.PHONE_SHARE)) {
            val share = shareDao.byName(server.id, name)
            if (share != null && mediaFileDao.countInShare(share.id) > 0) return
        }
        serverDao.delete(server.id)
    }

    /**
     * The root folder of Phone storage ([DeviceSource.PHONE_SHARE]), made on
     * first use. [PhoneLibrary] hangs one folder per phone directory off it.
     */
    suspend fun phoneRootFolder(): FolderEntity = rootFolder(DeviceSource.PHONE_SHARE)

    /**
     * The folder adopted copies land in, made on first use. (Its
     * [rootFolder] is shared with Phone storage.)
     *
     * `lastListedAtMs` is set rather than left null on purpose: null means
     * "the scan has not walked this yet" and renders as "Not listed yet".
     * Nothing will ever walk this folder — there is no share to walk — so
     * leaving it null would park a permanent lie in Browse.
     */
    private suspend fun deviceRootFolder(): FolderEntity = rootFolder(DeviceSource.SHARE)

    /** One of the device server's shares and its root folder, server and share made on first use. */
    private suspend fun rootFolder(shareName: String): FolderEntity {
        val now = System.currentTimeMillis()
        val server = serverDao.byHost(DeviceSource.HOST, PORT) ?: serverDao.byId(
            serverDao.insert(
                ServerEntity(
                    name = DeviceSource.NAME,
                    host = DeviceSource.HOST,
                    port = PORT,
                    authMode = AuthMode.GUEST.name,
                    username = null,
                    lastSeenAtMs = now,
                    createdAtMs = now,
                ),
            ),
        )!!
        val share = shareDao.byName(server.id, shareName) ?: shareDao.byId(
            shareDao.insert(
                ShareEntity(
                    serverId = server.id,
                    name = shareName,
                    // Enabled, or the Library wall would not read it: the wall
                    // is built from the ENABLED shares, and a source nobody can
                    // switch on has no way to become visible.
                    enabled = true,
                    freeBytes = null,
                    totalBytes = null,
                    lastScanAtMs = now,
                    // There is no share to write a chapter file to.
                    writeChapters = false,
                ),
            ),
        )!!
        return folderDao.upsert(
            FolderEntity(
                shareId = share.id, parentId = null, relPath = "", name = shareName,
                fileCount = 0, byteCount = 0, lastListedAtMs = now,
            ),
        )
    }

    private companion object {
        /** Nothing dials it; the column is not nullable and 445 is the least surprising value. */
        const val PORT = 445
        const val TAG = "Regolith/Device"
    }
}
