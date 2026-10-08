package com.regolith.data.pictures

import android.content.Context
import android.util.Log
import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.key.Keyer
import coil3.request.Options
import com.regolith.data.db.ServerDao
import com.regolith.data.db.ShareDao
import com.regolith.data.db.ShareFileDao
import com.regolith.data.repository.SourceRepository
import com.regolith.domain.media.LocalSource
import com.regolith.domain.media.MediaFileTypes
import com.regolith.domain.smb.SmbGateway
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A picture at its own full size, for the lightbox: what the image view is
 * handed instead of the small thumbnail ([com.regolith.domain.artwork.ArtworkKind.PICTURE]).
 * [version] is the picture's size and date on the share, so a picture
 * replaced there under the same name is fetched again rather than served
 * from the copy kept here — the `?v=` of a URL.
 */
data class PictureOriginal(val pictureId: Long, val version: String)

/**
 * Pictures copied off the share whole, kept in the app's cache directory so
 * swiping back to one is instant and a story does not fetch it twice.
 *
 * The cache directory, not the artwork directory: these are big (3–25 MB
 * a photo), cheap to fetch again, and Android may clear them when space
 * runs short, which is exactly right for them. It is also held to
 * [LIMIT_BYTES] here, oldest first, so a long story through a big album
 * does not leave a gigabyte behind. Web analogy: the browser's HTTP cache,
 * where the artwork directory is the app's own IndexedDB.
 */
@Singleton
class PictureOriginals @Inject constructor(
    @ApplicationContext context: Context,
    private val gateway: SmbGateway,
    private val sources: SourceRepository,
    private val shareDao: ShareDao,
    private val serverDao: ServerDao,
    private val shareFileDao: ShareFileDao,
) {
    private val directory = File(context.cacheDir, DIRECTORY)
    private val locks = ConcurrentHashMap<Long, Mutex>()

    /** The whole picture on the phone, fetched first if need be; null when the share cannot hand it over. */
    suspend fun file(original: PictureOriginal): File? = file(original.pictureId)

    /** The same, for [pictureId] as it is on the share now: what saving it to the phone or making a poster of it reads. */
    suspend fun file(pictureId: Long): File? {
        val row = shareFileDao.byId(pictureId) ?: return null
        val file = File(directory, "${row.id}-${row.sizeBytes}-${row.modifiedAtMs}.${MediaFileTypes.extensionOf(row.name)}")
        if (file.exists()) {
            // Most recently used, for the trim below.
            file.setLastModified(System.currentTimeMillis())
            return file
        }
        return locks.getOrPut(row.id) { Mutex() }.withLock {
            if (file.exists()) return@withLock file
            val share = shareDao.byId(row.shareId) ?: return@withLock null
            val server = serverDao.byId(share.serverId) ?: return@withLock null
            if (LocalSource.isLocal(server.host)) return@withLock null
            withContext(Dispatchers.IO) {
                directory.mkdirs()
                val partial = File(directory, file.name + ".part")
                try {
                    gateway.open(sources.hostFor(server.id), sources.credentialsFor(server.id), share.name, row.relPath).use { source ->
                        FileOutputStream(partial).use { out ->
                            val buffer = ByteArray(BUFFER)
                            var at = 0L
                            while (true) {
                                val n = source.readAt(at, buffer, 0, buffer.size)
                                if (n <= 0) break
                                out.write(buffer, 0, n)
                                at += n
                            }
                        }
                    }
                    if (!partial.renameTo(file)) throw IOException("could not keep ${file.name}")
                    // An older copy of the same picture, replaced on the share since, goes now.
                    directory.listFiles { f -> f.name.startsWith("${row.id}-") && f.name != file.name }?.forEach { it.delete() }
                    trim()
                    file
                } catch (e: Exception) {
                    partial.delete()
                    Log.w(TAG, "${row.name}: not fetched (${e.message})")
                    null
                }
            }
        }
    }

    /** Held to [LIMIT_BYTES], the least recently opened going first. */
    private fun trim() {
        val files = directory.listFiles { f -> f.isFile && !f.name.endsWith(".part") }?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        for (f in files) {
            if (total <= LIMIT_BYTES) break
            total -= f.length()
            f.delete()
        }
    }

    private companion object {
        const val TAG = "Regolith/Pictures"
        const val DIRECTORY = "pictures"
        const val BUFFER = 256 * 1024

        /** About fifteen big phone photos, or forty ordinary ones. */
        const val LIMIT_BYTES = 256L * 1024 * 1024
    }
}

/**
 * Teaches Coil what a [PictureOriginal] is: the file [PictureOriginals]
 * keeps, typed by its extension so the right decoder reads it (a GIF or an
 * animated WebP moves; a HEIC turns the way its `irot` says).
 */
class PictureOriginalFetcher(private val original: PictureOriginal, private val originals: PictureOriginals) : Fetcher {
    override suspend fun fetch(): FetchResult {
        val file = originals.file(original) ?: throw IOException("picture ${original.pictureId} could not be fetched")
        return SourceFetchResult(
            source = ImageSource(file.toOkioPath(), FileSystem.SYSTEM),
            mimeType = mimeTypeOf(file.extension),
            dataSource = DataSource.DISK,
        )
    }

    class Factory @Inject constructor(private val originals: PictureOriginals) : Fetcher.Factory<PictureOriginal> {
        override fun create(data: PictureOriginal, options: Options, imageLoader: ImageLoader): Fetcher = PictureOriginalFetcher(data, originals)
    }

    companion object {
        fun mimeTypeOf(extension: String): String? = when (extension.lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            "heic", "heif" -> "image/heic"
            "avif" -> "image/avif"
            else -> null
        }
    }
}

/** Memory-cache key: the picture and its version, so a replaced picture is not drawn from memory. */
class PictureOriginalKeyer @Inject constructor() : Keyer<PictureOriginal> {
    override fun key(data: PictureOriginal, options: Options): String = "picture-original:${data.pictureId}:${data.version}"
}
