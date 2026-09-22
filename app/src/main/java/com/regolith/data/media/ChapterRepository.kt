package com.regolith.data.media

import android.util.Log
import com.regolith.data.db.MediaFileDao
import com.regolith.data.db.ServerDao
import com.regolith.data.db.ShareDao
import com.regolith.data.repository.SourceRepository
import com.regolith.domain.media.ChapterParser
import com.regolith.domain.playback.Chapter
import com.regolith.domain.smb.SeekableByteSource
import com.regolith.domain.smb.SmbFailure
import com.regolith.domain.smb.SmbGateway
import com.regolith.domain.smb.SmbHost
import com.regolith.player.LocalMedia
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.RandomAccessFile
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A file's chapter markers, read from the container itself.
 *
 * The parsing is [ChapterParser]'s; this opens the bytes — a copy on this
 * device if there is one, otherwise the share — and remembers what it
 * found, because the answer cannot change while a file is loaded and
 * asking twice would mean opening the file twice.
 *
 * A file with no chapters caches its empty list too. "Nothing here" is an
 * answer worth keeping: without it, every load of a chapterless file (which
 * is most files) would re-read the container header off the share.
 */
@Singleton
class ChapterRepository @Inject constructor(
    private val mediaFileDao: MediaFileDao,
    private val shareDao: ShareDao,
    private val serverDao: ServerDao,
    private val sources: SourceRepository,
    private val gateway: SmbGateway,
    private val local: LocalMedia,
) {
    private val cache = mutableMapOf<Long, List<Chapter>>()

    /** Empty when the file has no chapters, or could not be read at all. */
    suspend fun chapters(fileId: Long): List<Chapter> {
        cache[fileId]?.let { return it }
        val found = withContext(Dispatchers.IO) { read(fileId) }
        synchronized(cache) {
            // A library is not big enough for this to matter, but an
            // unbounded map on a long-lived singleton is a leak by default.
            if (cache.size > MAX_CACHED) cache.clear()
            cache[fileId] = found
        }
        return found
    }

    private suspend fun read(fileId: Long): List<Chapter> = try {
        openSource(fileId)?.use { source ->
            ChapterParser.read(source).also {
                if (it.isNotEmpty()) Log.d(TAG, "file $fileId: ${it.size} chapters")
            }
        } ?: emptyList()
    } catch (e: SmbFailure) {
        // Out of reach is not "no chapters" — but it is also not worth a
        // retry loop, and the file is not playing either.
        Log.d(TAG, "chapters for $fileId deferred: ${e.message}")
        emptyList()
    } catch (e: Exception) {
        Log.w(TAG, "chapters for $fileId failed: $e")
        emptyList()
    }

    private suspend fun openSource(fileId: Long): SeekableByteSource? {
        local.file(fileId)?.let { return FileSource(it) }
        val file = mediaFileDao.byId(fileId) ?: return null
        val share = shareDao.byId(file.shareId) ?: return null
        val server = serverDao.byId(share.serverId) ?: return null
        return gateway.open(
            sources.hostFor(server.id),
            sources.credentialsFor(server.id),
            share.name,
            file.relPath,
        )
    }

    /** A copy on this device, read the way the share is read. */
    private class FileSource(file: java.io.File) : SeekableByteSource {
        private val handle = RandomAccessFile(file, "r")
        override val size: Long = handle.length()

        override fun readAt(offset: Long, dst: ByteArray, dstOffset: Int, length: Int): Int = synchronized(handle) {
            if (offset >= size) return -1
            handle.seek(offset)
            handle.read(dst, dstOffset, length)
        }

        override fun close() {
            runCatching { handle.close() }
        }
    }

    private companion object {
        const val TAG = "Regolith/Chapters"
        const val MAX_CACHED = 200
    }
}
