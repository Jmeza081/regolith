package com.regolith.data.repository

import com.regolith.data.db.FolderDao
import com.regolith.data.db.FolderEntity
import com.regolith.data.db.MediaFileDao
import com.regolith.data.db.MediaFileEntity
import com.regolith.data.db.PlaybackProgressDao
import com.regolith.data.db.PlaybackProgressEntity
import com.regolith.data.db.RecentSearchDao
import com.regolith.data.db.RecentSearchEntity
import com.regolith.data.db.ServerDao
import com.regolith.data.db.ShareDao
import com.regolith.domain.library.FolderClassifier
import com.regolith.domain.library.FolderKind
import com.regolith.domain.library.TitleParser
import com.regolith.domain.media.MediaFileTypes
import com.regolith.domain.media.MediaInfo
import com.regolith.domain.model.BrowseItem
import com.regolith.domain.smb.SmbFailure
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
    private val recentSearchDao: RecentSearchDao,
) {
    /** What one listing produced: the subfolders to walk next and how many playable files were seen. */
    data class FolderOutcome(val subfolders: List<FolderEntity>, val fileCount: Int)

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
    suspend fun refreshFolder(folderId: Long): FolderOutcome {
        val folder = checkNotNull(folderDao.byId(folderId)) { "folder $folderId" }
        val share = checkNotNull(shareDao.byId(folder.shareId)) { "share ${folder.shareId}" }
        val server = checkNotNull(serverDao.byId(share.serverId)) { "server ${share.serverId}" }
        val host = SmbHost(server.host, server.port)
        val credentials = sources.credentialsFor(server.id)

        val entries = try {
            gateway.list(host, credentials, share.name, folder.relPath)
        } catch (e: SmbFailure.Unreachable) {
            sources.markUnreachable(server.id)
            throw e
        }
        sources.markReachable(server.id)
        val now = System.currentTimeMillis()
        val prefix = if (folder.relPath.isEmpty()) "" else "${folder.relPath}/"

        val dirPaths = mutableListOf<String>()
        val subfolders = mutableListOf<FolderEntity>()
        val filePaths = mutableListOf<String>()
        var fileCount = 0
        var byteCount = 0L
        for (e in entries) {
            val relPath = prefix + e.name
            if (e.isDirectory) {
                dirPaths += relPath
                val parsed = TitleParser.parseFolderName(e.name)
                subfolders += folderDao.upsert(
                    FolderEntity(
                        shareId = share.id, parentId = folder.id, relPath = relPath, name = e.name,
                        fileCount = 0, byteCount = 0, lastListedAtMs = null,
                        titleParsed = parsed.title, year = parsed.year,
                    ),
                )
            } else if (MediaFileTypes.isVideo(e.name)) {
                filePaths += relPath
                fileCount++
                byteCount += e.sizeBytes
                val parsed = TitleParser.parseVideoName(e.name)
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
                        titleParsed = parsed.title,
                        year = parsed.year,
                        season = parsed.season,
                        episode = parsed.episode,
                    ),
                )
            }
        }
        // SQLite's NOT IN () with an empty list is fine in Room; it removes everything.
        folderDao.deleteChildrenNotIn(folder.id, dirPaths)
        mediaFileDao.markMissingNotIn(folder.id, filePaths)
        val kind = FolderClassifier.classify(folder.name, isRoot = folder.parentId == null, entryNames = entries.map { it.name }) { n ->
            entries.first { it.name == n }.isDirectory
        }
        val parsed = TitleParser.parseFolderName(folder.name)
        folderDao.update(
            folder.copy(
                fileCount = fileCount, byteCount = byteCount, lastListedAtMs = now,
                kind = kind.name, titleParsed = parsed.title, year = parsed.year,
            ),
        )
        return FolderOutcome(subfolders, fileCount)
    }

    suspend fun markScanned(shareId: Long) {
        shareDao.byId(shareId)?.let { shareDao.update(it.copy(lastScanAtMs = System.currentTimeMillis())) }
    }

    // --- Library / Home / Search reads (design sections 04, 05, 06)

    /** Root folders of the enabled shares, once each has been visited. */
    fun observeRoots(shareIds: List<Long>): Flow<List<FolderEntity>> = folderDao.observeRoots(shareIds)

    /** Every folder row of the given shares; the Library walks the tree in memory. */
    fun observeFoldersInShares(shareIds: List<Long>): Flow<List<FolderEntity>> =
        if (shareIds.isEmpty()) flowOf(emptyList()) else folderDao.observeInShares(shareIds)

    fun observeFilesIn(folderIds: List<Long>): Flow<List<MediaFileEntity>> =
        if (folderIds.isEmpty()) flowOf(emptyList()) else mediaFileDao.observeInFolders(folderIds)

    fun observeFilesByIds(ids: List<Long>): Flow<List<MediaFileEntity>> =
        if (ids.isEmpty()) flowOf(emptyList()) else mediaFileDao.observeByIds(ids)

    fun observeFilesInShares(shareIds: List<Long>): Flow<List<MediaFileEntity>> =
        if (shareIds.isEmpty()) flowOf(emptyList()) else mediaFileDao.observeInShares(shareIds)

    fun observeFileCount(shareIds: List<Long>): Flow<Int> = if (shareIds.isEmpty()) flowOf(0) else mediaFileDao.observeCountInShares(shareIds)

    fun observeNewest(limit: Int): Flow<List<MediaFileEntity>> = mediaFileDao.observeNewest(limit)

    fun observeContinueWatching(limit: Int): Flow<List<MediaFileEntity>> = mediaFileDao.observeContinueWatching(limit)

    fun observeProgress(fileIds: List<Long>): Flow<List<PlaybackProgressEntity>> =
        if (fileIds.isEmpty()) flowOf(emptyList()) else progressDao.observeForFiles(fileIds)

    /** Every folder in the subtree of [folderId], itself included (the folder kinds tell what they are). */
    suspend fun descendants(folderId: Long): List<FolderEntity> {
        val out = mutableListOf<FolderEntity>()
        val queue = ArrayDeque<Long>().apply { add(folderId) }
        folderDao.byId(folderId)?.let { out += it }
        while (queue.isNotEmpty()) {
            val children = folderDao.children(queue.removeFirst())
            out += children
            queue.addAll(children.map { it.id })
        }
        return out
    }

    /**
     * Prefix search over filenames, parsed titles and paths. The query is
     * turned into an FTS MATCH expression: every word must match as a
     * prefix, so "samou" finds "Le.Samourai.1967" and "Samouraï cuts".
     */
    fun searchFiles(query: String, limit: Int): Flow<List<MediaFileEntity>> {
        val match = ftsMatch(query) ?: return flowOf(emptyList())
        return mediaFileDao.search(match, limit)
    }

    fun searchFolders(query: String, limit: Int): Flow<List<FolderEntity>> {
        val match = ftsMatch(query) ?: return flowOf(emptyList())
        return folderDao.searchFolders(match, limit)
    }

    fun observeRecentSearches(limit: Int): Flow<List<RecentSearchEntity>> = recentSearchDao.observeRecent(limit)
    suspend fun rememberSearch(query: String) = recentSearchDao.upsert(RecentSearchEntity(query.trim(), System.currentTimeMillis()))
    suspend fun clearRecentSearches() = recentSearchDao.clear()

    companion object {
        /** `samou rai` -> `"samou"* "rai"*`; null when there is nothing to search for. */
        fun ftsMatch(query: String): String? {
            val words = query.split(Regex("""[\s.\-_/]+""")).map { it.trim().replace("\"", "").lowercase() }.filter { it.isNotEmpty() }
            if (words.isEmpty()) return null
            return words.joinToString(" ") { "\"$it\"*" }
        }
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
