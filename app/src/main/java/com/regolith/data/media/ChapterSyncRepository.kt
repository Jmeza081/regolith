package com.regolith.data.media

import android.util.Log
import com.regolith.data.db.ChapterSyncDao
import com.regolith.data.db.ChapterSyncEntity
import com.regolith.data.db.MediaFileDao
import com.regolith.data.db.ServerDao
import com.regolith.data.db.ShareDao
import com.regolith.data.db.UserChapterDao
import com.regolith.data.db.UserChapterEntity
import com.regolith.domain.media.ChapterSidecar
import com.regolith.domain.media.DemoSource
import com.regolith.domain.playback.Chapter
import com.regolith.domain.playback.ChapterSync
import com.regolith.domain.playback.ChapterSyncNote
import com.regolith.domain.playback.ChapterSyncState
import com.regolith.domain.smb.CredentialSource
import com.regolith.domain.smb.SmbCredentials
import com.regolith.domain.smb.SmbEntry
import com.regolith.domain.smb.SmbFailure
import com.regolith.domain.smb.SmbHost
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps the phone's chapter rows and the sidecar on the share in step
 * (P10). The file is the durable copy; the rows are its cache and the
 * search index. Three rules, none of which merges:
 *
 *  - **Import.** [onFolderListed] runs on every scan and Browse listing:
 *    a sidecar whose modified time moved since it was last imported
 *    replaces the rows. A sidecar that vanished takes its rows with it.
 *  - **Write.** [markDirty] after an edit; [syncDirty] (the worker) writes
 *    the file. A Forbidden is a read-only share: the rows stay, the sheet
 *    says so, and the next scan tries again.
 *  - **Conflict.** Both changed since the last sync: the newer one wins,
 *    whole, and a note says which way it went.
 *
 * Settings › Clear is [clearLocal] and never touches the share (the
 * owner's rule); Revert is [revert] and deletes the file too.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class ChapterSyncRepository @Inject constructor(
    private val syncDao: ChapterSyncDao,
    private val chapterDao: UserChapterDao,
    private val mediaFileDao: MediaFileDao,
    private val shareDao: ShareDao,
    private val serverDao: ServerDao,
    private val credentials: CredentialSource,
    private val writer: SidecarWriter,
    private val scheduler: ChapterSyncScheduler,
) {
    /** The sheet's state for one film, live. */
    fun observe(fileId: Long): Flow<ChapterSync> {
        val writeEnabled = mediaFileDao.observe(fileId).flatMapLatest { file ->
            if (file == null) flowOf(true) else shareDao.observe(file.shareId).map { it?.writeChapters ?: true }
        }
        return combine(syncDao.observeForFile(fileId), chapterDao.observeForFile(fileId), writeEnabled) { row, rows, enabled ->
            ChapterSync(state = stateOf(row, rows.isNotEmpty(), enabled), note = row?.note?.let { n -> runCatching { ChapterSyncNote.valueOf(n) }.getOrNull() })
        }
    }

    /** After an edit on this phone: remember that the share is behind, and ask the worker to catch it up. */
    suspend fun markDirty(fileId: Long) {
        val row = syncDao.byFile(fileId)
        syncDao.upsert(
            ChapterSyncEntity(
                fileId = fileId, shareMtimeMs = row?.shareMtimeMs, dirty = true, origin = ORIGIN_LOCAL, note = null, updatedAtMs = System.currentTimeMillis(),
            ),
        )
        scheduler.enqueue()
    }

    /**
     * A folder was just listed off the share: import every sidecar that is
     * new or changed, and drop the rows of one that is gone. Called from
     * the scan and from Browse; a failure on one film never stops the rest
     * or the listing.
     */
    suspend fun onFolderListed(folderId: Long, folderRelPath: String, entries: List<SmbEntry>, host: SmbHost, creds: SmbCredentials, share: String) {
        val sidecars = entries.filter { !it.isDirectory }.mapNotNull { e -> ChapterSidecar.basenameOf(e.name)?.let { it to e } }.toMap()
        if (sidecars.isEmpty() && syncDao.countForFolder(folderId) == 0) return
        val prefix = if (folderRelPath.isEmpty()) "" else "$folderRelPath/"
        for (video in mediaFileDao.inFolder(folderId)) {
            try {
                val entry = sidecars[video.name.substringBeforeLast('.')]
                val row = syncDao.byFile(video.id)
                if (entry == null) {
                    // The file went away, and these rows were its cache.
                    if (row != null && row.shareMtimeMs != null && !row.dirty) {
                        chapterDao.deleteForFile(video.id)
                        syncDao.delete(video.id)
                    }
                    continue
                }
                if (row?.shareMtimeMs == entry.modifiedAtMs) continue
                var note: String? = null
                if (row?.dirty == true) {
                    val local = chapterDao.lastUpdated(video.id) ?: 0L
                    if (local > entry.modifiedAtMs) continue // ours is newer; the worker writes over it
                    note = ChapterSyncNote.REPLACED_BY_SHARE.name
                }
                val text = writer.read(host, creds, share, prefix + entry.name) ?: continue
                val chapters = ChapterSidecar.parse(text, video.durationMs ?: 0L)
                val now = System.currentTimeMillis()
                chapterDao.replaceForFile(video.id, chapters.map { UserChapterEntity(fileId = video.id, startMs = it.startMs, title = it.title, updatedAtMs = now) })
                syncDao.upsert(ChapterSyncEntity(video.id, shareMtimeMs = entry.modifiedAtMs, dirty = false, origin = ORIGIN_SHARE, note = note, updatedAtMs = now))
                Log.d(TAG, "imported ${chapters.size} chapters for ${video.relPath}")
            } catch (e: Exception) {
                Log.w(TAG, "sidecar import for ${video.relPath} failed: $e")
            }
        }
    }

    /**
     * Write every dirty film's sidecar. Returns false when a share was out
     * of reach, so the worker can retry with backoff; every other failure
     * is recorded on the row and the rest carry on.
     */
    suspend fun syncDirty(): Boolean {
        var reachable = true
        for (row in syncDao.dirty()) {
            val file = mediaFileDao.byId(row.fileId) ?: run { syncDao.delete(row.fileId); continue }
            val share = shareDao.byId(file.shareId) ?: continue
            val server = serverDao.byId(share.serverId) ?: continue
            if (DemoSource.isDemo(server.host) || !share.writeChapters) continue
            val host = SmbHost(server.host, server.port)
            val creds = credentials.credentialsFor(server.id)
            val folder = file.relPath.substringBeforeLast('/', "")
            val chapters = chapterDao.forFile(row.fileId).map { Chapter(it.startMs, it.title) }
            try {
                val mtime = if (chapters.isEmpty()) {
                    writer.delete(host, creds, share.name, folder, file.name); null
                } else {
                    writer.write(host, creds, share.name, folder, file.name, ChapterSidecar.format(chapters))
                }
                syncDao.upsert(row.copy(dirty = false, shareMtimeMs = mtime, origin = ORIGIN_LOCAL, note = null, updatedAtMs = System.currentTimeMillis()))
            } catch (e: SmbFailure.Forbidden) {
                syncDao.upsert(row.copy(note = ChapterSyncNote.READ_ONLY_NOTE, updatedAtMs = System.currentTimeMillis()))
            } catch (e: SmbFailure.AuthFailed) {
                // The account can read but not write: the same thing as far as the sheet is concerned.
                syncDao.upsert(row.copy(note = ChapterSyncNote.READ_ONLY_NOTE, updatedAtMs = System.currentTimeMillis()))
            } catch (e: SmbFailure.Unreachable) {
                reachable = false
            } catch (e: SmbFailure) {
                Log.w(TAG, "sidecar write for ${file.relPath} failed: $e")
                syncDao.upsert(row.copy(note = ChapterSyncNote.WRITE_FAILED.name, updatedAtMs = System.currentTimeMillis()))
            }
        }
        return reachable
    }

    /**
     * Back to the film's own markers or the even split: the rows go, and
     * so does the file. When the file cannot be deleted the rows still go,
     * a [ChapterSyncNote.FILE_STAYS] is left for the sheet, and the next
     * scan brings the chapters back from the file — which is the truth.
     */
    suspend fun revert(fileId: Long) {
        chapterDao.deleteForFile(fileId)
        val row = syncDao.byFile(fileId)
        if (row == null || (row.shareMtimeMs == null && !row.dirty)) { syncDao.delete(fileId); return }
        val file = mediaFileDao.byId(fileId) ?: run { syncDao.delete(fileId); return }
        val share = shareDao.byId(file.shareId) ?: run { syncDao.delete(fileId); return }
        val server = serverDao.byId(share.serverId) ?: run { syncDao.delete(fileId); return }
        if (DemoSource.isDemo(server.host) || !share.writeChapters || row.shareMtimeMs == null) { syncDao.delete(fileId); return }
        try {
            writer.delete(SmbHost(server.host, server.port), credentials.credentialsFor(server.id), share.name, file.relPath.substringBeforeLast('/', ""), file.name)
            syncDao.delete(fileId)
        } catch (e: SmbFailure) {
            Log.w(TAG, "sidecar delete for ${file.relPath} failed: $e")
            syncDao.upsert(row.copy(dirty = false, note = ChapterSyncNote.FILE_STAYS.name, updatedAtMs = System.currentTimeMillis()))
        }
    }

    /** Settings › Chapters › Clear: the phone's cache only. Films with a file on the share get theirs back at the next scan. */
    suspend fun clearLocal() {
        chapterDao.deleteAll()
        syncDao.deleteAll()
    }

    /** The one-time note has been shown. */
    suspend fun clearNote(fileId: Long) = syncDao.clearNote(fileId)

    private fun stateOf(row: ChapterSyncEntity?, hasRows: Boolean, writeEnabled: Boolean): ChapterSyncState? = when {
        !hasRows -> null
        row == null -> ChapterSyncState.PHONE_ONLY
        // Writing turned off for the share outranks everything the row remembers.
        row.dirty && !writeEnabled -> ChapterSyncState.PHONE_ONLY
        row.dirty && row.note == ChapterSyncNote.READ_ONLY_NOTE -> ChapterSyncState.READ_ONLY
        row.dirty -> ChapterSyncState.WAITING
        row.shareMtimeMs != null -> if (row.origin == ORIGIN_SHARE) ChapterSyncState.FROM_SHARE else ChapterSyncState.ON_SHARE
        else -> ChapterSyncState.PHONE_ONLY
    }

    companion object {
        private const val TAG = "Regolith/ChapterSync"
        const val ORIGIN_LOCAL = "LOCAL"
        const val ORIGIN_SHARE = "SHARE"
    }
}
