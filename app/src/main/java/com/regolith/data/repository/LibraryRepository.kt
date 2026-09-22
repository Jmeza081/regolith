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
import com.regolith.data.db.ShareRootDao
import com.regolith.domain.library.FolderClassifier
import com.regolith.domain.library.FolderKind
import com.regolith.domain.library.TitleParser
import com.regolith.domain.media.MediaFileTypes
import com.regolith.domain.media.MediaInfo
import com.regolith.domain.model.BrowseItem
import com.regolith.domain.model.rootsCover
import com.regolith.domain.smb.SmbFailure
import com.regolith.domain.smb.SmbGateway
import android.util.Log
import com.regolith.domain.smb.SmbEntry
import com.regolith.domain.smb.SmbCredentials
import com.regolith.domain.smb.SmbHost
import com.regolith.domain.smb.listingRetryDelayMs
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton
import com.regolith.domain.media.DemoSource
import com.regolith.domain.media.LocalSource
import com.regolith.domain.media.ShortsRule

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
    private val shareRootDao: ShareRootDao,
    private val folderDao: FolderDao,
    private val mediaFileDao: MediaFileDao,
    private val progressDao: PlaybackProgressDao,
    private val recentSearchDao: RecentSearchDao,
    private val artwork: com.regolith.data.artwork.ArtworkRepository,
    private val chapterSync: com.regolith.data.media.ChapterSyncRepository,
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
                            shareId = f.shareId,
                            folderRelPath = f.relPath.substringBeforeLast('/', ""),
                        )
                    }
                }
            }
        }
        return combine(folders, filesWithProgress) { dirs, fs ->
            dirs.map {
                BrowseItem.Folder(
                    id = it.id, name = it.name, fileCount = it.fileCount, byteCount = it.byteCount,
                    shareId = it.shareId, relPath = it.relPath, listed = it.lastListedAtMs != null,
                )
            } + fs
        }
    }

    /** How many playable files a set of shares holds, live. */
    fun observeFileCountInShares(shareIds: List<Long>): Flow<Int> = mediaFileDao.observeCountInShares(shareIds)

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
        // Nothing to re-list: a source on the phone has no share behind it,
        // so its rows are all there ever was (the demo library, and the copies
        // adopted from a disconnected server).
        if (LocalSource.isLocal(server.host)) return FolderOutcome(folderDao.children(folderId), folder.fileCount)
        // A share narrowed to chosen folders (schema v5): anything that is
        // not a chosen folder, or inside one, is never read off the share —
        // not by the scan and not by Browse opening it. That includes the
        // share root and the folders on the way down to a deep pick, which
        // get rows so the tree keeps its shape and nothing more.
        val roots = shareRootDao.pathsFor(share.id)
        if (!rootsCover(roots, folder.relPath)) return refreshSkeleton(folder, share.id, roots)
        val host = sources.hostFor(server.id)
        val credentials = sources.credentialsFor(server.id)

        val entries = listWithRetry(host, credentials, share.name, folder.relPath, server.id)
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
        // A poster.jpg dropped into the folder since the last scan beats the
        // mosaic the app stitched for it; this is the rescan that notices.
        artwork.onFolderListed(folder.id, entries)
        // Likewise a chapter file beside a film (P10): new or changed, it
        // replaces the phone's copy; gone, it takes its copy with it.
        chapterSync.onFolderListed(folder.id, folder.relPath, entries, host, credentials, share.name)
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

    /**
     * List one folder, with one more go if the connection dropped.
     *
     * A scan is thousands of listings in a row, and before this a single
     * dropped connection anywhere in that sequence marked the whole server
     * out of reach and ended the walk. On a LAN that is almost always the
     * truth. Over a VPN relay it is almost always a blip — the tunnel
     * re-keyed, or the relay closed a socket it thought was idle — and the
     * very next attempt succeeds.
     *
     * [listingRetryDelayMs] decides, so what is retried and what is not is
     * stated in one place and tested.
     */
    private suspend fun listWithRetry(
        host: SmbHost,
        credentials: SmbCredentials,
        share: String,
        relPath: String,
        serverId: Long,
    ): List<SmbEntry> {
        var attempt = 1
        while (true) {
            try {
                return gateway.list(host, credentials, share, relPath)
            } catch (e: SmbFailure) {
                val pause = listingRetryDelayMs(attempt, e)
                if (pause == null) {
                    // Only a transport failure says anything about the SERVER.
                    if (e is SmbFailure.Unreachable) sources.markUnreachable(serverId)
                    throw e
                }
                Log.i(TAG, "listing '$relPath' failed (${e.message}); one more go in ${pause}ms")
                delay(pause)
                attempt++
            }
        }
    }

    /**
     * One folder of the skeleton a narrowed share hangs on: the share root,
     * or one of the folders on the way down to a chosen one.
     *
     * It is never listed over the network. Its children are exactly the next
     * step towards the chosen folders, so walking the skeleton costs no SMB
     * calls at all and nothing the user left out is ever read — not by the
     * scan, and not by Browse, which re-lists whatever folder you open.
     *
     * Every child is handed back, chosen or not, so the scan's queue walks
     * the skeleton down to the chosen folders and then lists those for real.
     *
     * The folders in between matter enough to keep. A first attempt hung
     * every chosen folder directly off the share root, which put `Season 02`
     * beside `Films` with "Series/Severance" thrown away, and one `Season 02`
     * looks much like another.
     */
    private suspend fun refreshSkeleton(folder: FolderEntity, shareId: Long, roots: List<String>): FolderOutcome {
        val chosen = roots.toSet()
        // The folders on the way down, minus any chosen outright: a path that
        // is both is walked for real, so it is not part of the skeleton.
        val between = roots.flatMap { ancestorsOf(it) }.toSet() - chosen
        val prefix = if (folder.relPath.isEmpty()) "" else "${folder.relPath}/"
        val childPaths = (between + chosen)
            .filter { it.startsWith(prefix) && it != folder.relPath && !it.removePrefix(prefix).contains('/') }
            .sorted()
        val children = childPaths.map { relPath ->
            val name = relPath.substringAfterLast('/')
            val parsed = TitleParser.parseFolderName(name)
            folderDao.upsert(
                FolderEntity(
                    shareId = shareId, parentId = folder.id, relPath = relPath, name = name,
                    fileCount = 0, byteCount = 0, lastListedAtMs = null,
                    // A skeleton folder was never listed, so nothing classified
                    // it. It holds folders, which is what a collection is. A
                    // chosen one is left alone for its own listing to classify.
                    kind = if (relPath in chosen) null else FolderKind.COLLECTION.name,
                    titleParsed = parsed.title, year = parsed.year,
                ),
            )
        }
        // Un-picking a folder has to take its rows with it, and a skeleton
        // folder left on an abandoned branch would be an empty dead end.
        folderDao.deleteChildrenNotIn(folder.id, childPaths)
        mediaFileDao.markMissingNotIn(folder.id, emptyList())
        folderDao.update(
            folder.copy(
                fileCount = 0, byteCount = 0, lastListedAtMs = System.currentTimeMillis(),
                kind = if (folder.parentId == null) FolderKind.ROOT.name else FolderKind.COLLECTION.name,
            ),
        )
        return FolderOutcome(children, 0)
    }

    /** "a/b/c" -> ["a", "a/b"]. The folders a path passes through, nearest the share root first. */
    private fun ancestorsOf(relPath: String): List<String> {
        val parts = relPath.split('/')
        return (1 until parts.size).map { parts.take(it).joinToString("/") }
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

    /**
     * The Shorts feed: every measured file on these shares that is vertical
     * and a minute or less, newest first.
     *
     * Rows only exist for paths a scan walked, so this needs no `share_roots`
     * test of its own -- a folder outside the chosen roots has no files here
     * to find. Files the artwork walk has not reached yet have no size on
     * the row and are simply absent, which is what [ShortsRule] means by
     * refusing to guess.
     */
    fun observeShorts(shareIds: List<Long>, maxDurationMs: Long): Flow<List<MediaFileEntity>> =
        if (shareIds.isEmpty()) {
            flowOf(emptyList())
        } else {
            mediaFileDao.observeShortCandidates(shareIds, maxDurationMs)
                .map { rows -> rows.filter { ShortsRule.isShort(it.durationMs, it.width, it.height, it.rotationDegrees, maxDurationMs) } }
        }

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
     * Every playable file in the subtree of [folderId], as Room knows it.
     *
     * Chunked because SQLite caps a statement at 999 bound variables and a
     * share can have thousands of folders. This is the exact expansion a
     * download uses once the subtree has been listed; [listSubtree] is what
     * lists it first when it has not.
     */
    suspend fun filesUnder(folderId: Long): List<MediaFileEntity> =
        descendants(folderId).map { it.id }.chunked(FILES_CHUNK).flatMap { mediaFileDao.inFolders(it) }

    /** Folders in the subtree that have never been listed off the share, so their counts mean nothing. */
    suspend fun unlistedUnder(folderId: Long): Int = descendants(folderId).count { it.lastListedAtMs == null }

    /**
     * List the whole subtree of [folderId] off the share, then return every
     * playable file in it.
     *
     * The same breadth-first walk the scan does, scoped to one folder: it
     * is the only way to download a folder the user has never opened, since
     * a folder nobody listed has no file rows to expand into. Each listing
     * goes through [refreshFolder], so a share narrowed to chosen folders
     * stays narrowed — a download cannot widen the library.
     *
     * [onProgress] is called with the running file count as folders are
     * listed, which is what the notification's "Finding files… 34" reports.
     * Breadth-first matters for more than tidiness: [onFolderListed] hands
     * back each folder's files as they are found, so copying can start
     * after the FIRST folder rather than after the last.
     *
     * Throws [SmbFailure] if the share stops answering, which the caller
     * turns into a paused batch rather than a failed one.
     */
    suspend fun listSubtree(
        folderId: Long,
        onProgress: suspend (found: Int) -> Unit = {},
        onFolderListed: suspend (List<MediaFileEntity>) -> Unit = {},
        /** True for a subfolder relPath the walk should not enter: a subtree the user left out. */
        prune: (relPath: String) -> Boolean = { false },
    ): List<MediaFileEntity> {
        val seen = LinkedHashMap<Long, MediaFileEntity>()
        val queue = ArrayDeque<Long>().apply { add(folderId) }
        val walked = mutableSetOf<Long>()
        while (queue.isNotEmpty()) {
            val id = queue.removeFirst()
            if (!walked.add(id)) continue
            val outcome = refreshFolder(id)
            queue.addAll(outcome.subfolders.filterNot { prune(it.relPath) }.map { it.id })
            val files = mediaFileDao.inFolders(listOf(id))
            val fresh = files.filter { it.id !in seen }
            fresh.forEach { seen[it.id] = it }
            if (fresh.isNotEmpty()) onFolderListed(fresh)
            onProgress(seen.size)
        }
        return seen.values.toList()
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
        private const val TAG = "Regolith/SMB"

        /** SQLite binds at most 999 variables per statement; 900 leaves room for the rest of the query. */
        private const val FILES_CHUNK = 900

        /**
         * `samou rai` -> `"samou*" "rai*"`; null when there is nothing to search for.
         *
         * The star sits INSIDE the quotes: that is FTS3/4's prefix syntax
         * (`"lin* ope*"`). Outside them — FTS5's syntax — SQLite 3.44 quietly
         * treats the term as a whole word, which is what the first version
         * of this did, so "samou" never found "Samouraï" (fixed in P9).
         * Quoting each word keeps `or`, `not` and `-` from acting as operators.
         */
        fun ftsMatch(query: String): String? {
            val words = query.split(Regex("""[\s.\-_/]+""")).map { it.trim().replace("\"", "").replace("*", "").lowercase() }.filter { it.isNotEmpty() }
            if (words.isEmpty()) return null
            return words.joinToString(" ") { "\"$it*\"" }
        }
    }

    suspend fun file(fileId: Long): MediaFileEntity? = mediaFileDao.byId(fileId)

    fun observeFile(fileId: Long): Flow<MediaFileEntity?> = mediaFileDao.observe(fileId)

    suspend fun folder(folderId: Long): FolderEntity? = folderDao.byId(folderId)

    suspend fun filesInFolder(folderId: Long): List<MediaFileEntity> = mediaFileDao.inFolder(folderId)

    /**
     * The folders directly inside one folder, as Room knows them. The move
     * sheet walks these rather than listing the share again: the destination
     * is chosen from what has already been seen, and the move itself is what
     * talks to the server.
     */
    suspend fun subfolders(folderId: Long): List<FolderEntity> =
        folderDao.children(folderId).sortedBy { it.name.lowercase() }

    /** Remember what the container probe found, so Title Detail and the chips never probe twice. */
    suspend fun saveProbe(fileId: Long, info: MediaInfo) {
        mediaFileDao.saveProbe(
            id = fileId,
            durationMs = info.durationMs,
            width = info.width,
            height = info.height,
            rotationDegrees = info.rotationDegrees,
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

    /**
     * The files of an explicit playback queue (Play all / Shuffle), in the
     * queue's own order. Missing files drop out silently: a queue built from
     * a wall you were looking at can outlive a rescan.
     */
    suspend fun filesInOrder(ids: List<Long>): List<MediaFileEntity> {
        if (ids.isEmpty()) return emptyList()
        val byId = mediaFileDao.byIds(ids).associateBy { it.id }
        return ids.mapNotNull { byId[it] }
    }

    /** "Next in this folder": the files after [fileId] in its folder, in name order. */
    suspend fun filesAfter(fileId: Long): List<MediaFileEntity> {
        val file = mediaFileDao.byId(fileId) ?: return emptyList()
        val siblings = mediaFileDao.inFolder(file.folderId)
        val index = siblings.indexOfFirst { it.id == fileId }
        return if (index < 0) emptyList() else siblings.drop(index + 1)
    }

    /** The file immediately before [fileId] in its folder: the player's Previous. */
    suspend fun fileBefore(fileId: Long): MediaFileEntity? {
        val file = mediaFileDao.byId(fileId) ?: return null
        val siblings = mediaFileDao.inFolder(file.folderId)
        val index = siblings.indexOfFirst { it.id == fileId }
        return if (index <= 0) null else siblings[index - 1]
    }

}
