package com.regolith.data.repository

import com.regolith.data.db.FolderDao
import com.regolith.data.db.FolderEntity
import com.regolith.data.db.MediaFileDao
import com.regolith.data.db.MediaFileEntity
import com.regolith.data.db.PlaybackProgressDao
import com.regolith.data.db.ServerDao
import com.regolith.data.db.ShareDao
import com.regolith.domain.media.MediaFileTypes
import com.regolith.domain.media.MediaInfo
import com.regolith.domain.model.BrowseItem
import com.regolith.domain.smb.SmbGateway
import com.regolith.domain.smb.SmbHost
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Folders and files as the app knows them. The Browse screen reads from
 * Room only (guardrail G3); [refreshFolder] is what talks to the share and
 * writes what it finds, so a listing survives the share going away and
 * every file has a stable id before anyone plays it.
 */
@Singleton
class LibraryRepository @Inject constructor(
    private val gateway: SmbGateway,
    private val sources: SourceRepository,
    private val serverDao: ServerDao,
    private val shareDao: ShareDao,
    private val folderDao: FolderDao,
    private val mediaFileDao: MediaFileDao,
    private val progressDao: PlaybackProgressDao,
) {
    fun observeFolder(folderId: Long): Flow<FolderEntity?> = folderDao.observe(folderId)

    /** Live contents of one folder: subfolders first, then files with their progress. */
    fun observeContents(folderId: Long): Flow<List<BrowseItem>> {
        val folders = folderDao.observeChildren(folderId)
        val files = mediaFileDao.observeInFolder(folderId)
        val filesWithProgress = files.flatMapLatest { list ->
            if (list.isEmpty()) {
                flowOf(emptyList())
            } else {
                progressDao.observeForFiles(list.map { it.id }).combine(flowOf(list)) { progress, files ->
                    val byId = progress.associateBy { it.fileId }
                    files.map { f ->
                        val p = byId[f.id]
                        BrowseItem.File(
                            id = f.id,
                            name = f.name,
                            sizeBytes = f.sizeBytes,
                            modifiedAtMs = f.modifiedAtMs,
                            progressMs = p?.positionMs,
                            durationMs = p?.durationMs ?: f.durationMs,
                            width = f.width,
                            height = f.height,
                        )
                    }
                }
            }
        }
        return combine(folders, filesWithProgress) { dirs, fs ->
            dirs.map { BrowseItem.Folder(id = it.id, name = it.name, fileCount = it.fileCount, byteCount = it.byteCount) } + fs
        }
    }

    /** The root folder row of a share, created on first use. */
    suspend fun rootFolder(shareId: Long): FolderEntity {
        val share = checkNotNull(shareDao.byId(shareId)) { "share $shareId" }
        return folderDao.upsert(
            FolderEntity(shareId = shareId, parentId = null, relPath = "", name = share.name, fileCount = 0, byteCount = 0, lastListedAtMs = null),
        )
    }

    /**
     * List [folderId] on the share and reconcile Room with what came back.
     * Files that vanished are marked missing, not deleted (they keep their
     * progress); subfolders that vanished are removed.
     * Throws [com.regolith.domain.smb.SmbFailure] if the share cannot be reached.
     */
    suspend fun refreshFolder(folderId: Long) {
        val folder = checkNotNull(folderDao.byId(folderId)) { "folder $folderId" }
        val share = checkNotNull(shareDao.byId(folder.shareId)) { "share ${folder.shareId}" }
        val server = checkNotNull(serverDao.byId(share.serverId)) { "server ${share.serverId}" }
        val host = SmbHost(server.host, server.port)
        val credentials = sources.credentialsFor(server.id)

        val entries = gateway.list(host, credentials, share.name, folder.relPath)
        val now = System.currentTimeMillis()
        val prefix = if (folder.relPath.isEmpty()) "" else "${folder.relPath}/"

        val dirPaths = mutableListOf<String>()
        val filePaths = mutableListOf<String>()
        var fileCount = 0
        var byteCount = 0L
        for (e in entries) {
            val relPath = prefix + e.name
            if (e.isDirectory) {
                dirPaths += relPath
                folderDao.upsert(
                    FolderEntity(shareId = share.id, parentId = folder.id, relPath = relPath, name = e.name, fileCount = 0, byteCount = 0, lastListedAtMs = null),
                )
            } else if (MediaFileTypes.isVideo(e.name)) {
                filePaths += relPath
                fileCount++
                byteCount += e.sizeBytes
                mediaFileDao.upsert(
                    MediaFileEntity(
                        shareId = share.id,
                        folderId = folder.id,
                        relPath = relPath,
                        name = e.name,
                        ext = MediaFileTypes.extensionOf(e.name),
                        sizeBytes = e.sizeBytes,
                        modifiedAtMs = e.modifiedAtMs,
                        durationMs = null,
                        missing = false,
                        addedAtMs = now,
                        lastSeenAtMs = now,
                    ),
                )
            }
        }
        // SQLite's NOT IN () with an empty list is fine in Room; it removes everything.
        folderDao.deleteChildrenNotIn(folder.id, dirPaths)
        mediaFileDao.markMissingNotIn(folder.id, filePaths)
        folderDao.update(folder.copy(fileCount = fileCount, byteCount = byteCount, lastListedAtMs = now))
    }

    suspend fun file(fileId: Long): MediaFileEntity? = mediaFileDao.byId(fileId)

    fun observeFile(fileId: Long): Flow<MediaFileEntity?> = mediaFileDao.observe(fileId)

    /** Remember what the container probe found, so Title Detail and the chips never probe twice. */
    suspend fun saveProbe(fileId: Long, info: MediaInfo) {
        mediaFileDao.saveProbe(
            id = fileId,
            durationMs = info.durationMs,
            width = info.width,
            height = info.height,
            frameRate = info.frameRate,
            videoCodec = info.videoMimeType,
            hdr = info.hdr,
            audioCodec = info.audioMimeType,
            audioChannels = info.audioChannels,
            audioSampleRate = info.audioSampleRate,
            probedAtMs = System.currentTimeMillis(),
        )
    }

    /** "TOWER · media": the server and share a file lives on, for the player's meta line. */
    suspend fun shareLabel(shareId: Long): String {
        val share = shareDao.byId(shareId) ?: return ""
        val server = serverDao.byId(share.serverId) ?: return share.name
        return "${server.name} · ${share.name}"
    }

    /** "Next in this folder": the files after [fileId] in its folder, in name order. */
    suspend fun filesAfter(fileId: Long): List<MediaFileEntity> {
        val file = mediaFileDao.byId(fileId) ?: return emptyList()
        val siblings = mediaFileDao.inFolder(file.folderId)
        val index = siblings.indexOfFirst { it.id == fileId }
        return if (index < 0) emptyList() else siblings.drop(index + 1)
    }
}
