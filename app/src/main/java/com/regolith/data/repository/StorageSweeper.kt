package com.regolith.data.repository

import android.util.Log
import com.regolith.data.artwork.ArtworkStore
import com.regolith.data.db.ArtworkDao
import com.regolith.data.db.FolderDao
import com.regolith.data.db.MediaFileDao
import com.regolith.data.db.TransferDao
import com.regolith.data.transfer.DownloadStore
import kotlinx.coroutines.flow.first
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bytes on the phone that nothing in the database claims any more.
 *
 * Two app-private directories hold files whose only record lives in Room:
 * `filesDir/downloads` (keyed by `transfers.localPath`) and
 * `filesDir/artwork` (keyed by `artwork.relPath`). Rows in both can be
 * deleted by a cascade — and a cascade cannot reach a file. Before this
 * existed, disconnecting a server left its downloads and its whole wall of
 * thumbnails on disk with nothing pointing at them, still counted by the
 * two "used" figures in Settings and by the header on the On-this-device
 * page, both of which measure the DIRECTORY rather than the rows.
 *
 * [DeviceLibrary] is what stops downloads being orphaned from here on.
 * This is what collects what was orphaned before it, and what catches the
 * cases nothing else owns: a file deleted on the share taking its artwork
 * with it, the app's storage being cleared under a row, a copy interrupted
 * by a process death.
 *
 * Runs once per app start, off the main thread. Deliberately conservative:
 * it only ever deletes a file that NO row names, so the worst a bug here
 * can cost is a thumbnail that gets made again.
 *
 * Web analogy: a garbage collector over blob storage, reconciling against
 * the table that is supposed to own every blob.
 */
@Singleton
class StorageSweeper @Inject constructor(
    private val transferDao: TransferDao,
    private val artworkDao: ArtworkDao,
    private val mediaFileDao: MediaFileDao,
    private val folderDao: FolderDao,
    private val downloads: DownloadStore,
    private val artwork: ArtworkStore,
    private val deviceLibrary: DeviceLibrary,
) {
    /** What one pass reclaimed, for the log line. */
    data class Swept(val downloadFiles: Int, val artworkRows: Int, val artworkDirs: Int, val bytes: Long)

    suspend fun sweep(): Swept {
        val downloads = sweepDownloads()
        val (rows, dirs, artBytes) = sweepArtwork()
        // The device source is created by the first adoption and has to be
        // taken away again by something; an empty "This device" in Settings
        // is worse than none.
        deviceLibrary.removeIfEmpty()
        val swept = Swept(downloads.first, rows, dirs, downloads.second + artBytes)
        if (swept.downloadFiles > 0 || swept.artworkRows > 0 || swept.artworkDirs > 0) {
            Log.i(
                TAG,
                "swept ${swept.downloadFiles} downloads, ${swept.artworkRows} artwork rows, " +
                    "${swept.artworkDirs} artwork dirs, ${swept.bytes} bytes",
            )
        }
        return swept
    }

    /**
     * Files in the downloads directory that no transfer row names.
     *
     * A row claims three paths, not one — the copy, the `.part` it is being
     * appended to, and the chapter file kept beside it (P10) — so all three
     * are spared for a row that is still queued or still copying.
     */
    private suspend fun sweepDownloads(): Pair<Int, Long> {
        val root = downloads.root
        if (!root.isDirectory) return 0 to 0L
        val claimed = transferDao.observeAll().first().flatMapTo(mutableSetOf()) { row ->
            listOf(row.localPath, "${row.localPath}.part", "${row.localPath}.chapters.txt")
        }
        var count = 0
        var bytes = 0L
        for (file in root.listFiles().orEmpty()) {
            if (!file.isFile || file.name in claimed) continue
            val size = file.length()
            if (file.delete()) {
                count++
                bytes += size
            }
        }
        return count to bytes
    }

    /**
     * Artwork whose owner has gone, both the rows and the images.
     *
     * The rows go first, by the owner ids Room can still see; then the
     * directories, which catches images left behind by an earlier version
     * that deleted rows without them. `artwork` has no foreign key by
     * design (guardrail G5 — a rescan that keeps a file id keeps its
     * pictures), so this pass is the only thing that ever collects them.
     */
    private suspend fun sweepArtwork(): Triple<Int, Int, Long> {
        val orphans = artworkDao.orphans()
        var bytes = 0L
        if (orphans.isNotEmpty()) {
            // The whole owner directory, not one file: every kind and every
            // variant it held belongs to the same departed owner.
            orphans.map { "${it.ownerType}/${it.ownerId}" }.distinct().forEach { path ->
                val dir = File(artwork.root, path)
                bytes += dir.sizeOnDisk()
                dir.deleteRecursively()
            }
            artworkDao.deleteByIds(orphans.map { it.id })
        }

        val root = artwork.root
        if (!root.isDirectory) return Triple(orphans.size, 0, bytes)
        val fileIds = mediaFileDao.allIds().toSet()
        val folderIds = folderDao.allIds().toSet()
        var dirs = 0
        for (typeDir in root.listFiles().orEmpty()) {
            if (!typeDir.isDirectory) continue
            // "moment" frames are keyed by the film's id, like "file".
            val live = when (typeDir.name) {
                "file", "moment" -> fileIds
                "folder" -> folderIds
                else -> continue
            }
            for (ownerDir in typeDir.listFiles().orEmpty()) {
                val id = ownerDir.name.toLongOrNull() ?: continue
                if (id in live) continue
                bytes += ownerDir.sizeOnDisk()
                if (ownerDir.deleteRecursively()) dirs++
            }
        }
        return Triple(orphans.size, dirs, bytes)
    }

    private fun File.sizeOnDisk(): Long =
        if (isDirectory) walkBottomUp().filter { it.isFile }.sumOf { it.length() } else if (isFile) length() else 0L

    private companion object {
        const val TAG = "Regolith/Sweep"
    }
}
