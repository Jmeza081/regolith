package com.regolith.data.fileops

import android.util.Log
import com.regolith.data.db.FolderDao
import com.regolith.data.db.MediaFileDao
import com.regolith.data.db.MediaFileEntity
import com.regolith.data.db.ServerDao
import com.regolith.data.db.ShareDao
import com.regolith.data.db.TransferDao
import com.regolith.data.transfer.DownloadStore
import com.regolith.domain.fileops.FileNames
import com.regolith.domain.fileops.FileOpError
import com.regolith.domain.fileops.FileOpFailure
import com.regolith.domain.fileops.FileOpResult
import com.regolith.domain.library.TitleParser
import com.regolith.domain.media.ChapterSidecar
import com.regolith.domain.media.DemoSource
import com.regolith.domain.smb.CredentialSource
import com.regolith.domain.smb.SmbCredentials
import com.regolith.domain.smb.SmbFailure
import com.regolith.domain.smb.SmbGateway
import com.regolith.domain.smb.SmbHost
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Renaming, moving and deleting files ON THE SHARE — the only part of the
 * app that changes someone's media, as opposed to reading it.
 *
 * **Why there is no worker behind this.** The download queue has one
 * because copying gigabytes takes minutes and must survive the process
 * dying. These do not copy: a move is a single server-side metadata
 * operation (measured at 32 MB in 7 ms), so a batch of forty is a fraction
 * of a second. A queue would be machinery guarding against a window that
 * is not open.
 *
 * **A batch is N independent operations, not a transaction.** That is not
 * a compromise, it is what the protocol gives: each file is either changed
 * or not, never half — so when the share drops in the middle, everything
 * already done stays done, the rest is untouched, and the result says so.
 * Nothing here can leave a half-written file behind.
 *
 * **Row ids survive.** A rename or move edits `media_files` in place, so
 * the file keeps its id and therefore its chapters, its resume point, its
 * artwork and its downloaded copy. A delete is the one case that takes the
 * row away, and its cascades are what the confirm dialog promises.
 *
 * Files only. Folders are never touched: a folder move would drag a whole
 * subtree of rows behind it, and a folder delete is the one mistake that
 * cannot be walked back.
 */
@Singleton
class FileOpsRepository @Inject constructor(
    private val gateway: SmbGateway,
    private val serverDao: ServerDao,
    private val shareDao: ShareDao,
    private val folderDao: FolderDao,
    private val mediaFileDao: MediaFileDao,
    private val credentials: CredentialSource,
    private val transfers: TransferDao,
    private val downloads: DownloadStore,
) {
    private companion object {
        const val TAG = "Regolith/FileOps"
    }

    /** Where a share is and who we are on it. */
    private data class ShareCtx(val host: SmbHost, val creds: SmbCredentials, val share: String)

    /**
     * Give [fileId] a new base name, keeping its extension.
     *
     * The destination is listed first so a clash is a sentence in the
     * dialog rather than a server error — and even if that listing is
     * stale, the rename itself is non-replacing, so the worst case is a
     * refusal, never a file quietly destroyed.
     */
    suspend fun rename(fileId: Long, newBaseName: String): FileOpResult {
        val file = mediaFileDao.byId(fileId) ?: return FileOpResult.failed(fileId, "", FileOpError.NOT_FOUND)
        val base = FileNames.cleanBase(newBaseName)
            ?: return FileOpResult.failed(fileId, file.name, FileOpError.BAD_NAME)
        val newName = FileNames.withExtension(base, file.ext)
        // Renaming a file to what it is already called is a no-op, not an error.
        if (newName == file.name) return FileOpResult(done = listOf(fileId))
        val ctx = ctxFor(file.shareId) ?: return FileOpResult.failed(fileId, file.name, FileOpError.FORBIDDEN)
        val parent = file.relPath.substringBeforeLast('/', "")
        val target = join(parent, newName)
        return try {
            // One listing answers both questions: is the new name free, and
            // does this film have a chapter file sitting beside it.
            val here = namesIn(ctx, parent)
            if (newName.lowercase() in here) {
                return FileOpResult.failed(fileId, file.name, FileOpError.NAME_TAKEN)
            }
            gateway.rename(ctx.host, ctx.creds, ctx.share, file.relPath, target)
            if (hasSidecar(file.name, here)) followSidecar(ctx, file.name, file.relPath, newName, target)
            mediaFileDao.update(reparsed(file.copy(relPath = target, name = newName)))
            FileOpResult(done = listOf(fileId))
        } catch (e: SmbFailure) {
            Log.w(TAG, "rename of ${file.name} failed", e)
            FileOpResult.failed(fileId, file.name, e.toError())
        }
    }

    /**
     * Move [fileIds] into [destFolderId]. Every file must be on the same
     * share as the destination: a rename cannot cross shares — the server
     * answers "cannot rename between different trees" — and a cross-share
     * move would mean copy-then-delete, the shape that loses files.
     */
    suspend fun move(fileIds: Collection<Long>, destFolderId: Long): FileOpResult {
        val dest = folderDao.byId(destFolderId) ?: return FileOpResult(failures = fileIds.map { FileOpFailure(it, "", FileOpError.NOT_FOUND) })
        val rows = mediaFileDao.byIds(fileIds.toList())
        if (rows.isEmpty()) return FileOpResult()
        val ctx = ctxFor(dest.shareId) ?: return FileOpResult(failures = rows.map { FileOpFailure(it.id, it.name, FileOpError.FORBIDDEN) })

        val done = mutableListOf<Long>()
        val failures = mutableListOf<FileOpFailure>()
        val touched = mutableSetOf(destFolderId)
        // One listing for the whole batch, then kept in step as names land.
        val taken = try {
            namesIn(ctx, dest.relPath).toMutableSet()
        } catch (e: SmbFailure) {
            return FileOpResult(failures = rows.map { FileOpFailure(it.id, it.name, e.toError()) })
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

        for (row in rows) {
            if (dropped) {
                failures += FileOpFailure(row.id, row.name, FileOpError.UNREACHABLE)
                continue
            }
            if (row.shareId != dest.shareId) {
                failures += FileOpFailure(row.id, row.name, FileOpError.OTHER)
                continue
            }
            // Already where it is being sent: nothing to do, and not a failure.
            if (row.folderId == destFolderId) {
                done += row.id
                continue
            }
            if (row.name.lowercase() in taken) {
                failures += FileOpFailure(row.id, row.name, FileOpError.NAME_TAKEN)
                continue
            }
            val target = join(dest.relPath, row.name)
            try {
                gateway.rename(ctx.host, ctx.creds, ctx.share, row.relPath, target)
                if (hasSidecar(row.name, namesInSource(row.relPath.substringBeforeLast('/', "")))) {
                    followSidecar(ctx, row.name, row.relPath, row.name, target)
                }
                touched += row.folderId
                mediaFileDao.update(row.copy(folderId = destFolderId, relPath = target))
                taken += row.name.lowercase()
                done += row.id
            } catch (e: SmbFailure.Unreachable) {
                // The share is gone; the rest of the batch would only queue
                // up the same failure. Stop and say how far it got.
                Log.w(TAG, "share dropped during move after ${done.size}", e)
                dropped = true
                failures += FileOpFailure(row.id, row.name, FileOpError.UNREACHABLE)
            } catch (e: SmbFailure) {
                Log.w(TAG, "move of ${row.name} failed", e)
                failures += FileOpFailure(row.id, row.name, e.toError())
            }
        }
        recount(touched)
        return FileOpResult(done, failures)
    }

    /**
     * Delete [fileIds] from the share for good, along with each file's
     * chapter sidecar and any copy kept on this device — the row is going,
     * so a copy left behind would be bytes nothing can reach.
     *
     * A file open for playback right now is a special case the server
     * handles for us: it leaves directory listings immediately and is
     * unlinked when the last reader closes it. The delete does not fail,
     * and the player does not break mid-frame.
     */
    suspend fun delete(fileIds: Collection<Long>): FileOpResult {
        val rows = mediaFileDao.byIds(fileIds.toList())
        if (rows.isEmpty()) return FileOpResult()
        val done = mutableListOf<Long>()
        val failures = mutableListOf<FileOpFailure>()
        val touched = mutableSetOf<Long>()
        var dropped = false

        for (row in rows) {
            if (dropped) {
                failures += FileOpFailure(row.id, row.name, FileOpError.UNREACHABLE)
                continue
            }
            val ctx = ctxFor(row.shareId)
            if (ctx == null) {
                failures += FileOpFailure(row.id, row.name, FileOpError.FORBIDDEN)
                continue
            }
            try {
                gateway.delete(ctx.host, ctx.creds, ctx.share, row.relPath)
                sidecarPathFor(row.name, row.relPath)?.let { side ->
                    runCatching { gateway.delete(ctx.host, ctx.creds, ctx.share, side) }
                }
                // The copy on this device is a copy of something that no longer
                // exists. Its transfer ROW cascades away with the file row, but
                // the bytes on disk are ours to remove, and only this knows where
                // they are.
                transfers.byFile(row.id)?.let { downloads.delete(it.localPath) }
                touched += row.folderId
                done += row.id
            } catch (e: SmbFailure.Unreachable) {
                Log.w(TAG, "share dropped during delete after ${done.size}", e)
                dropped = true
                failures += FileOpFailure(row.id, row.name, FileOpError.UNREACHABLE)
            } catch (e: SmbFailure) {
                Log.w(TAG, "delete of ${row.name} failed", e)
                failures += FileOpFailure(row.id, row.name, e.toError())
            }
        }
        if (done.isNotEmpty()) mediaFileDao.deleteByIds(done)
        recount(touched)
        return FileOpResult(done, failures)
    }

    // ── plumbing ───────────────────────────────────────────────────────

    /** Null when the share cannot be written to at all (the demo library). */
    private suspend fun ctxFor(shareId: Long): ShareCtx? {
        val share = shareDao.byId(shareId) ?: return null
        val server = serverDao.byId(share.serverId) ?: return null
        if (DemoSource.isDemo(server.host)) return null
        return ShareCtx(SmbHost(server.host, server.port), credentials.credentialsFor(server.id), share.name)
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
