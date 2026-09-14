package com.regolith.desktop.data

import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.key.Keyer
import coil3.request.Options
import com.regolith.domain.artwork.ArtworkCandidates
import com.regolith.domain.smb.SmbGateway
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.Buffer
import okio.FileSystem

/**
 * One image file on a share, as something Coil can load: what a Browse row
 * hands the shared `ArtworkImage` on the Mac.
 *
 * The phone draws artwork from its own cache, named by database ids; the Mac
 * has no database, so it names an image by where it is. Which image a film
 * gets is decided from the folder listing Browse already has
 * ([ArtworkCandidates.forFile]), so showing one costs a single read.
 *
 * [gateway] travels with the model rather than living in the loader: Coil's
 * loader is set once per process, and the UI tests run the app against
 * different gateways in one JVM.
 *
 * Web analogy: an `<img>` whose `src` is a path on the share, with a custom
 * loader that knows how to fetch it.
 */
data class ShareImage(
    val gateway: SmbGateway,
    val connection: Connection,
    val relPath: String,
    val sizeBytes: Long,
)

/** Reads a [ShareImage]'s bytes off the share; Coil's Skia decoder turns them into a picture. */
class ShareImageFetcher(private val image: ShareImage) : Fetcher {
    override suspend fun fetch(): FetchResult = withContext(Dispatchers.IO) {
        val c = image.connection
        val bytes = image.gateway.open(c.host, c.credentials, c.share, image.relPath).use { src ->
            // The candidates were filtered by the design's 8 MB cap already; this
            // only guards a file that grew after the listing.
            require(src.size <= ArtworkCandidates.MAX_IMAGE_BYTES) { "${image.relPath} is over the artwork size limit" }
            val out = ByteArray(src.size.toInt())
            var read = 0
            while (read < out.size) {
                val n = src.readAt(read.toLong(), out, read, out.size - read)
                if (n < 0) break
                read += n
            }
            if (read == out.size) out else out.copyOf(read)
        }
        SourceFetchResult(
            source = ImageSource(Buffer().write(bytes), FileSystem.SYSTEM),
            mimeType = null,
            dataSource = DataSource.NETWORK,
        )
    }

    /** Registered with the loader; makes one fetcher per [ShareImage]. */
    class Factory : Fetcher.Factory<ShareImage> {
        override fun create(data: ShareImage, options: Options, imageLoader: ImageLoader): Fetcher = ShareImageFetcher(data)
    }
}

/**
 * The memory-cache key: the share, the path and the size. Without a keyer
 * Coil would not cache a custom model at all, and the size makes a replaced
 * image load again. The gateway and the credentials are deliberately left out.
 */
class ShareImageKeyer : Keyer<ShareImage> {
    override fun key(data: ShareImage, options: Options): String =
        "share:${data.connection.label}/${data.relPath}:${data.sizeBytes}"
}

/**
 * Sets up Coil for the Mac: its defaults plus [ShareImage]. Safe to call more
 * than once; only the first call in a process installs a loader, which is why
 * the loader holds no gateway of its own.
 */
fun installShareImageLoader() {
    SingletonImageLoader.setSafe { context -> buildShareImageLoader(context) }
}

/** The loader itself, separate so a test can use one without the process-wide singleton. */
fun buildShareImageLoader(context: PlatformContext): ImageLoader =
    ImageLoader.Builder(context)
        .components {
            add(ShareImageKeyer())
            add(ShareImageFetcher.Factory())
        }
        .build()
