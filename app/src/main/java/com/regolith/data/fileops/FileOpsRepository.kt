package com.regolith.data.fileops

import android.util.Log
import com.regolith.data.db.FolderDao
import com.regolith.data.db.FolderEntity
import com.regolith.data.db.MediaFileDao
import com.regolith.data.db.MediaFileEntity
import com.regolith.data.db.ServerDao
import com.regolith.data.db.ShareDao
import com.regolith.data.db.SubtreeDao
import com.regolith.data.db.TransferDao
import com.regolith.data.transfer.DownloadStore
import com.regolith.domain.fileops.FileNames
import com.regolith.domain.fileops.FileOpError
import com.regolith.domain.fileops.FileOpFailure
import com.regolith.domain.fileops.FileOpResult
import com.regolith.domain.fileops.FileOpTarget
import com.regolith.domain.library.TitleParser
import com.regolith.domain.media.ChapterSidecar
import com.regolith.domain.media.LocalSource
import com.regolith.domain.smb.ServerAccess
import com.regolith.domain.smb.SmbCredentials
import com.regolith.domain.smb.SmbFailure
import com.regolith.domain.smb.SmbGateway
import com.regolith.domain.smb.SmbHost
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Renaming, moving, deleting and creating things ON THE SHARE — the only
 * part of the app that changes someone's media, as opposed to reading it.
 *
 * **Why there is no worker behind this.** The download queue has one
 * because copying gigabytes takes minutes and must survive the process
 * dying. These do not copy: a move is a single server-side metadata
 * operation (measured at 32 MB in 7 ms), so a batch of forty is a fraction
 * of a second. A queue would be machinery guarding against a window that
 * is not open.
 *
 * **A batch is N independent operations, not a transaction.** That is not
 * a compromise, it is what the protocol gives: each item is either changed
 * or not, never half — so when the share drops in the middle, everything
 * already done stays done, the rest is untouched, and the result says so.
 * Nothing here can leave a half-written file behind.
 *
 * **Row ids survive.** A rename or move edits rows in place, so a file
 * keeps its id and therefore its chapters, its resume point, its artwork
 * and its downloaded copy. A delete is the one case that takes rows away,
 * and its cascades are what the confirm dialog promises.
 *
 * **Folders are included, and cost more than files do.** On the wire a
 * folder is no harder — the server renames a directory and its whole
 * subtree comes along in one operation. The work is local: `relPath` is
 * denormalised onto every row underneath, so a folder move rewrites a
 * subtree of the mirror ([SubtreeDao]). A folder DELETE is the one verb
 * with real teeth: the server's recursive delete takes files the app never
 * listed — subtitles, artwork, other formats — which is why it has its own
 * gateway call and its own sentence in the confirm dialog.
 */
@Singleton
class FileOpsRepository @Inject constructor(
    private val gateway: SmbGateway,
    private val serverDao: ServerDao,
    private val shareDao: ShareDao,
    private val folderDao: FolderDao,
    private val mediaFileDao: MediaFileDao,
    private val credentials: ServerAccess,
    private val transfers: TransferDao,
    private val downloads: DownloadStore,
    private val subtrees: SubtreeDao,
) {
    private companion object {
        const val TAG = "Regolith/FileOps"

        /**
         * SQLite caps a statement at 999 bound variables, and a deleted
         * folder can hold more rows than that. Same number, same reason, as
         * `LibraryRepository.FILES_CHUNK`.
         */
        const val ID_CHUNK = 500
    }

    /** Where a share is and who we are on it. */
    private data class ShareCtx(val host: SmbHost, val creds: SmbCredentials, val share: String)

    // ── rename ─────────────────────────────────────────────────────────

    /**
     * Give [target] a new name: a file keeps its extension, a folder is
     * called exactly what was typed.
     */
    suspend fun rename(target: FileOpTarget, newBaseName: String): FileOpResult =
        if (target.isFolder) renameFolder(target, newBaseName) else renameFile(target, newBaseName)

    /**
     * The destination is listed first so a clash is a sentence in the
     * dialog rather than a server error — and even if that listing is
     * stale, the rename itself is non-replacing, so the worst case is a
     * refusal, never a file quietly destroyed.
     */
    private suspend fun renameFile(target: FileOpTarget, newBaseName: String): FileOpResult {
        val file = mediaFileDao.byId(target.id) ?: return FileOpResult.failed(target, "", FileOpError.NOT_FOUND)
        val base = FileNames.cleanBase(newBaseName)
            ?: return FileOpResult.failed(target, file.name, FileOpError.BAD_NAME)
        val newName = FileNames.withExtension(base, file.ext)
        // Renaming a file to what it is already called is a no-op, not an error.
        if (newName == file.name) return FileOpResult(done = listOf(target))
        val ctx = ctxFor(file.shareId) ?: return FileOpResult.failed(target, file.name, FileOpError.FORBIDDEN)
        val parent = file.relPath.substringBeforeLast('/', "")
        val to = join(parent, newName)
        return try {
            // One listing answers both questions: is the new name free, and
            // does this film have a chapter file sitting beside it.
            val here = namesIn(ctx, parent)
            if (newName.lowercase() in here) {
                return FileOpResult.failed(target, file.name, FileOpError.NAME_TAKEN)
            }
            gateway.rename(ctx.host, ctx.creds, ctx.share, file.relPath, to)
            if (hasSidecar(file.name, here)) followSidecar(ctx, file.name, file.relPath, newName, to)
            mediaFileDao.update(reparsed(file.copy(relPath = to, name = newName)))
            FileOpResult(done = listOf(target))
        } catch (e: SmbFailure) {
            Log.w(TAG, "rename of ${file.name} failed", e)
            FileOpResult.failed(target, file.name, e.toError())
        }
    }

    /**
     * A folder rename is one call on the server whatever is inside it, and
     * a subtree rewrite here. The share's own root is refused: that name
     * belongs to the server, not to us.
     */
    private suspend fun renameFolder(target: FileOpTarget, newName: String): FileOpResult {
        val folder = folderDao.byId(target.id) ?: return FileOpResult.failed(target, "", FileOpError.NOT_FOUND)
        if (folder.parentId == null) return FileOpResult.failed(target, folder.name, FileOpError.BAD_DESTINATION)
        val name = FileNames.cleanFolderName(newName)
            ?: return FileOpResult.failed(target, folder.name, FileOpError.BAD_NAME)
        if (name == folder.name) return FileOpResult(done = listOf(target))
        val ctx = ctxFor(folder.shareId) ?: return FileOpResult.failed(target, folder.name, FileOpError.FORBIDDEN)
        val parentPath = folder.relPath.substringBeforeLast('/', "")
        val to = join(parentPath, name)
        return try {
            if (name.lowercase() in namesIn(ctx, parentPath)) {
                return FileOpResult.failed(target, folder.name, FileOpError.NAME_TAKEN)
            }
            gateway.rename(ctx.host, ctx.creds, ctx.share, folder.relPath, to)
            relocate(folder, to, name, folder.parentId)
            FileOpResult(done = listOf(target))
        } catch (e: SmbFailure) {
            Log.w(TAG, "rename of folder ${folder.name} failed", e)
            FileOpResult.failed(target, folder.name, e.toError())
        }
    }

    // ── move ───────────────────────────────────────────────────────────

    /**
     * Move [targets] into [destFolderId]. Everything must be on the same
     * share as the destination: a rename cannot cross shares — the server
     * answers "cannot rename between different trees" — and a cross-share
     * move would mean copy-then-delete, the shape that loses files.
     *
     * Folders and files mix freely, because each item is its own operation
     * either way. A folder aimed at itself or at its own subtree is refused
     * before anything is sent: the server would either error or, worse,
     * succeed at something nobody meant.
     */
    suspend fun move(targets: Collection<FileOpTarget>, destFolderId: Long): FileOpResult {
        val dest = folderDao.byId(destFolderId)
            ?: return FileOpResult(failures = targets.map { FileOpFailure(it, "", FileOpError.NOT_FOUND) })
        val items = itemsFor(targets)
        if (items.isEmpty()) return FileOpResult()
        val ctx = ctxFor(dest.shareId)
            ?: return FileOpResult(failures = items.map { FileOpFailure(it.target, it.name, FileOpError.FORBIDDEN) })

        val done = mutableListOf<FileOpTarget>()
        val failures = mutableListOf<FileOpFailure>()
        val touched = mutableSetOf(destFolderId)
        // One listing for the whole batch, then kept in step as names land.
        val taken = try {
            namesIn(ctx, dest.relPath).toMutableSet()
        } catch (e: SmbFailure) {
            return FileOpResult(failures = items.map { FileOpFailure(it.target, it.name, e.toError()) })
        }
        var dropped = false

        // Most films have no chapter file. Asking the folder once beats a
        // speculative rename per film to find out — a round trip each.
        val sourceNames = mutableMapOf<String, Set<String>>()
        suspend fun namesInSource(dir: String): Set<String> {
            sourceNames[dir]?.let { return it }
            val names = runCatching { namesIn(ctx, dir) }.getOrDefault(emptySet())
            sourceNames[dir] = names
            return names
        }

        for (item in items) {
            if (dropped) {
                failures += FileOpFailure(item.target, item.name, FileOpError.UNREACHABLE)
                continue
            }
            val refusal = refuseMove(item, dest)
            if (refusal != null) {
                failures += FileOpFailure(item.target, item.name, refusal)
                continue
            }
            // Already where it is being sent: nothing to do, and not a failure.
            if (item.parentId == destFolderId) {
                done += item.target
                continue
            }
            if (item.name.lowercase() in taken) {
                failures += FileOpFailure(item.target, item.name, FileOpError.NAME_TAKEN)
                continue
            }
            val to = join(dest.relPath, item.name)
            try {
                gateway.rename(ctx.host, ctx.creds, ctx.share, item.relPath, to)
                if (item.folder != null) {
                    relocate(item.folder, to, item.name, destFolderId)
                } else {
                    val row = checkNotNull(item.file)
                    if (hasSidecar(row.name, namesInSource(row.relPath.substringBeforeLast('/', "")))) {
                        followSidecar(ctx, row.name, row.relPath, row.name, to)
                    }
                    touched += row.folderId
                    mediaFileDao.update(row.copy(folderId = destFolderId, relPath = to))
                }
                taken += item.name.lowercase()
                done += item.target
            } catch (e: SmbFailure.Unreachable) {
                // The share is gone; the rest of the batch would only queue
                // up the same failure. Stop and say how far it got.
                Log.w(TAG, "share dropped during move after ${done.size}", e)
                dropped = true
                failures += FileOpFailure(item.target, item.name, FileOpError.UNREACHABLE)
            } catch (e: SmbFailure) {
                Log.w(TAG, "move of ${item.name} failed", e)
                failures += FileOpFailure(item.target, item.name, e.toError())
            }
        }
        recount(touched)
        return FileOpResult(done, failures)
    }

    // ── delete ─────────────────────────────────────────────────────────

    /**
     * Delete [targets] from the share for good, along with each file's
     * chapter sidecar and any copy kept on this device — the rows are
     * going, so a copy left behind would be bytes nothing can reach.
     *
     * A FOLDER takes everything under it, including files the app never
     * listed. That is the server's own recursive delete and there is no
     * finer instrument; the confirm dialog is the only gate.
     *
     * A file open for playback right now is a special case the server
     * handles for us: it leaves directory listings immediately and is
     * unlinked when the last reader closes it. The delete does not fail,
     * and the player does not break mid-frame.
     */
    suspend fun delete(targets: Collection<FileOpTarget>): FileOpResult {
        val items = itemsFor(targets)
        if (items.isEmpty()) return FileOpResult()
        val done = mutableListOf<FileOpTarget>()
        val failures = mutableListOf<FileOpFailure>()
        val goneFiles = mutableListOf<Long>()
        val goneFolders = mutableListOf<Long>()
        val touched = mutableSetOf<Long>()
        var dropped = false

        for (item in items) {
            if (dropped) {
                failures += FileOpFailure(item.target, item.name, FileOpError.UNREACHABLE)
                continue
            }
            val ctx = ctxFor(item.shareId)
            if (ctx == null) {
                failures += FileOpFailure(item.target, item.name, FileOpError.FORBIDDEN)
                continue
            }
            try {
                if (item.folder != null) {
                    // The share root is the share. Deleting it would empty
                    // someone's NAS from a row that only ever meant "here".
                    if (item.folder.parentId == null) {
                        failures += FileOpFailure(item.target, item.name, FileOpError.BAD_DESTINATION)
                        continue
                    }
                    gateway.deleteFolder(ctx.host, ctx.creds, ctx.share, item.relPath)
                    val subtree = subtreeOf(item.folder).map { it.id }
                    forgetDownloads(subtree.chunked(ID_CHUNK).flatMap { mediaFileDao.inFolders(it) }.map { it.id })
                    goneFolders += subtree
                    subtrees.dropRootsUnder(item.shareId, item.relPath, "${item.relPath}/", item.relPath.length + 1)
                    item.folder.parentId?.let { touched += it }
                } else {
                    val row = checkNotNull(item.file)
                    gateway.delete(ctx.host, ctx.creds, ctx.share, row.relPath)
                    sidecarPathFor(row.name, row.relPath)?.let { side ->
                        runCatching { gateway.delete(ctx.host, ctx.creds, ctx.share, side) }
                    }
                    forgetDownloads(listOf(row.id))
                    goneFiles += row.id
                    touched += row.folderId
                }
                done += item.target
            } catch (e: SmbFailure.Unreachable) {
                Log.w(TAG, "share dropped during delete after ${done.size}", e)
                dropped = true
                failures += FileOpFailure(item.target, item.name, FileOpError.UNREACHABLE)
            } catch (e: SmbFailure) {
                Log.w(TAG, "delete of ${item.name} failed", e)
                failures += FileOpFailure(item.target, item.name, e.toError())
            }
        }
        goneFiles.chunked(ID_CHUNK).forEach { mediaFileDao.deleteByIds(it) }
        // Files inside these folders cascade away with them (the foreign key
        // on `media_files.folderId`), and their chapters and progress cascade
        // in turn. Subfolders do not — `parentId` has no such key — which is
        // why the whole subtree is in the list.
        goneFolders.chunked(ID_CHUNK).forEach { folderDao.deleteByIds(it) }
        recount(touched - goneFolders.toSet())
        return FileOpResult(done, failures)
    }

    // ── create ─────────────────────────────────────────────────────────

    /**
     * Make an empty folder inside [parentFolderId] and return it as the one
     * item in [FileOpResult.done], so the caller can move things into it
     * without a second lookup.
     *
     * The row is written as LISTED (with a timestamp) rather than left for
     * the next walk to fill in: we just created it, so we know for certain
     * it holds nothing, and "not listed yet" on a folder made two seconds
     * ago reads as a bug.
     */
    suspend fun createFolder(parentFolderId: Long, rawName: String): FileOpResult {
        val target = FileOpTarget.folder(parentFolderId)
        val parent = folderDao.byId(parentFolderId)
            ?: return FileOpResult.failed(target, "", FileOpError.NOT_FOUND)
        val name = FileNames.cleanFolderName(rawName)
            ?: return FileOpResult.failed(target, rawName.trim(), FileOpError.BAD_NAME)
        val ctx = ctxFor(parent.shareId) ?: return FileOpResult.failed(target, name, FileOpError.FORBIDDEN)
        val relPath = join(parent.relPath, name)
        return try {
            if (name.lowercase() in namesIn(ctx, parent.relPath)) {
                return FileOpResult.failed(target, name, FileOpError.NAME_TAKEN)
            }
            gateway.mkdir(ctx.host, ctx.creds, ctx.share, relPath)
            val parsed = TitleParser.parseFolderName(name)
            val row = folderDao.upsert(
                FolderEntity(
                    shareId = parent.shareId,
                    parentId = parent.id,
                    relPath = relPath,
                    name = name,
                    fileCount = 0,
                    byteCount = 0,
                    lastListedAtMs = System.currentTimeMillis(),
                    titleParsed = parsed.title,
                    year = parsed.year,
                ),
            )
            FileOpResult(done = listOf(FileOpTarget.folder(row.id)))
        } catch (e: SmbFailure) {
            Log.w(TAG, "could not create $relPath", e)
            FileOpResult.failed(target, name, e.toError())
        }
    }

    // ── plumbing ───────────────────────────────────────────────────────

    /**
     * One item of a batch with everything the loop needs, whichever table
     * it came from. [parentId] is what "is it already there?" is asked of:
     * a file's folder, or a folder's parent.
     */
    private data class MoveItem(
        val target: FileOpTarget,
        val name: String,
        val shareId: Long,
        val parentId: Long?,
        val relPath: String,
        val folder: FolderEntity? = null,
        val file: MediaFileEntity? = null,
    )

    /** Read the rows for a batch, dropping ids nothing answers to. */
    private suspend fun itemsFor(targets: Collection<FileOpTarget>): List<MoveItem> {
        val files = mediaFileDao.byIds(targets.filterNot { it.isFolder }.map { it.id })
            .associateBy { it.id }
        return targets.mapNotNull { target ->
            if (target.isFolder) {
                folderDao.byId(target.id)?.let {
                    MoveItem(target, it.name, it.shareId, it.parentId, it.relPath, folder = it)
                }
            } else {
                files[target.id]?.let {
                    MoveItem(target, it.name, it.shareId, it.folderId, it.relPath, file = it)
                }
            }
        }
    }

    /** Why this item cannot go to this destination, or null when it can. */
    private fun refuseMove(item: MoveItem, dest: FolderEntity): FileOpError? = when {
        item.shareId != dest.shareId -> FileOpError.BAD_DESTINATION
        item.folder == null -> null
        // A share root is the share; it has nowhere to be moved to.
        item.folder.parentId == null -> FileOpError.BAD_DESTINATION
        // Into itself, or into its own subtree: the folder would have to
        // contain its own new parent.
        dest.id == item.folder.id -> FileOpError.BAD_DESTINATION
        dest.relPath == item.relPath || dest.relPath.startsWith("${item.relPath}/") -> FileOpError.BAD_DESTINATION
        else -> null
    }

    /**
     * Repoint a folder and everything under it, and reparse the title the
     * Library shows — a renamed folder is a differently-named season.
     */
    private suspend fun relocate(folder: FolderEntity, newRelPath: String, newName: String, newParentId: Long?) {
        if (newName != folder.name) {
            val parsed = TitleParser.parseFolderName(newName)
            folderDao.update(folder.copy(titleParsed = parsed.title, year = parsed.year))
        }
        subtrees.relocate(folder.id, folder.shareId, folder.relPath, newRelPath, newName, newParentId)
    }

    /** A folder and every folder beneath it, breadth-first. */
    private suspend fun subtreeOf(folder: FolderEntity): List<FolderEntity> {
        val all = mutableListOf(folder)
        var frontier = listOf(folder.id)
        while (frontier.isNotEmpty()) {
            val next = frontier.flatMap { folderDao.children(it) }
            all += next
            frontier = next.map { it.id }
        }
        return all
    }

    /**
     * The copies on this device are copies of something that no longer
     * exists. Their transfer ROWS cascade away with the file rows, but the
     * bytes on disk are ours to remove, and only this knows where they are.
     */
    private suspend fun forgetDownloads(fileIds: List<Long>) {
        for (id in fileIds) transfers.byFile(id)?.let { downloads.delete(it.localPath) }
    }

    /** Null when there is no share to write to: the demo library, or "On this device". */
    private suspend fun ctxFor(shareId: Long): ShareCtx? {
        val share = shareDao.byId(shareId) ?: return null
        val server = serverDao.byId(share.serverId) ?: return null
        if (LocalSource.isLocal(server.host)) return null
        return ShareCtx(credentials.hostFor(server.id), credentials.credentialsFor(server.id), share.name)
    }

    private suspend fun namesIn(ctx: ShareCtx, relPath: String): Set<String> =
        gateway.list(ctx.host, ctx.creds, ctx.share, relPath).map { it.name.lowercase() }.toSet()

    /**
     * The chapter file follows its film. Best effort on purpose: most films
     * have no sidecar, and a film that moved without its chapters is a far
     * smaller problem than a move reported as failed after it succeeded.
     */
    private suspend fun followSidecar(ctx: ShareCtx, oldName: String, oldRel: String, newName: String, newRel: String) {
        val from = sidecarPathFor(oldName, oldRel) ?: return
        val to = sidecarPathFor(newName, newRel) ?: return
        if (from == to) return
        runCatching { gateway.rename(ctx.host, ctx.creds, ctx.share, from, to) }
            .onFailure { Log.i(TAG, "no chapter file followed $oldName (${it.message})") }
    }

    /** Is there a chapter file beside this film, according to a folder listing? */
    private fun hasSidecar(videoName: String, names: Set<String>): Boolean =
        ChapterSidecar.sidecarNameFor(videoName)?.lowercase()?.let { it in names } == true

    private fun sidecarPathFor(videoName: String, videoRelPath: String): String? {
        val name = ChapterSidecar.sidecarNameFor(videoName) ?: return null
        return join(videoRelPath.substringBeforeLast('/', ""), name)
    }

    /**
     * Folder counts are what Browse and the selection bar add up, and
     * nothing else recomputes them until the next listing of that folder.
     * Cheap: the rows are already local.
     */
    private suspend fun recount(folderIds: Set<Long>) {
        for (id in folderIds) {
            val folder = folderDao.byId(id) ?: continue
            val files = mediaFileDao.inFolder(id)
            folderDao.update(folder.copy(fileCount = files.size, byteCount = files.sumOf { it.sizeBytes }))
        }
    }

    /** A renamed file is a differently-named film: Library must not keep showing the old title. */
    private fun reparsed(file: MediaFileEntity): MediaFileEntity {
        val parsed = TitleParser.parseVideoName(file.name)
        return file.copy(titleParsed = parsed.title, year = parsed.year, season = parsed.season, episode = parsed.episode)
    }

    private fun join(dir: String, name: String): String = if (dir.isEmpty()) name else "$dir/$name"

    private fun SmbFailure.toError(): FileOpError = when (this) {
        is SmbFailure.Forbidden, is SmbFailure.AuthFailed -> FileOpError.FORBIDDEN
        is SmbFailure.Unreachable -> FileOpError.UNREACHABLE
        is SmbFailure.NotFound -> FileOpError.NOT_FOUND
        else -> FileOpError.OTHER
    }
}
