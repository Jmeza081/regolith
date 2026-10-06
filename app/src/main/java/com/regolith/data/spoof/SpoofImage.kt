package com.regolith.data.spoof

import android.content.Context
import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.key.Keyer
import coil3.request.Options
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject

/**
 * A stock photo standing in for a picture of the library while spoof mode
 * is on ([Spoof.image]): what [com.regolith.ui.components.ArtworkImage]
 * hands Coil instead of the real [com.regolith.domain.artwork.ArtworkRequest].
 *
 * The photos come from Lorem Picsum, which serves Unsplash's photographs
 * by seed at any size, with no key: the same [seed] is the same photo every
 * time, so a video keeps its stand-in. Low resolution on purpose — a poster
 * is 200×300 — so a wall of them costs a few hundred kilobytes, once.
 *
 * Web analogy: `<img src="https://picsum.photos/seed/…/200/300">`, with the
 * browser cache swapped for a folder the app keeps.
 */
data class SpoofImage(val seed: String, val width: Int, val height: Int) {
    val url: String get() = "https://picsum.photos/seed/$seed/$width/$height.jpg"
}

/**
 * Fetches a [SpoofImage] once and keeps it in the app's cache directory,
 * which Android may clear when space runs short: the photo is simply
 * fetched again. Offline, the fetch fails and the tile shows its no-picture
 * art with the made-up name, which is a fine demo too.
 *
 * Plain `HttpURLConnection` rather than an HTTP library: it is one GET of
 * a small file that follows Picsum's redirect to its CDN, and the app has
 * no other use for one.
 */
class SpoofImageFetcher(
    private val image: SpoofImage,
    private val directory: File,
) : Fetcher {
    override suspend fun fetch(): FetchResult = withContext(Dispatchers.IO) {
        val file = File(directory, "${image.seed}-${image.width}x${image.height}.jpg")
        val fetched = !file.exists()
        if (fetched) download(file)
        SourceFetchResult(
            source = ImageSource(file.toOkioPath(), FileSystem.SYSTEM),
            mimeType = "image/jpeg",
            dataSource = if (fetched) DataSource.NETWORK else DataSource.DISK,
        )
    }

    private fun download(file: File) {
        directory.mkdirs()
        val connection = URL(image.url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.instanceFollowRedirects = true
            if (connection.responseCode != HttpURLConnection.HTTP_OK) throw IOException("stand-in photo: HTTP ${connection.responseCode}")
            // Written beside, then moved into place: two tiles asking for the
            // same photo at once must never read a half-written file.
            val partial = File.createTempFile(file.nameWithoutExtension, ".part", directory)
            connection.inputStream.use { input -> partial.outputStream().use { input.copyTo(it) } }
            if (!partial.renameTo(file)) partial.delete()
        } finally {
            connection.disconnect()
        }
    }

    class Factory @Inject constructor(@ApplicationContext private val context: Context) : Fetcher.Factory<SpoofImage> {
        override fun create(data: SpoofImage, options: Options, imageLoader: ImageLoader): Fetcher =
            SpoofImageFetcher(data, File(context.cacheDir, DIRECTORY))
    }

    companion object {
        /** Where the photos are kept, under the app's cache directory. */
        const val DIRECTORY = "spoof"

        private const val TIMEOUT_MS = 10_000
    }
}

/** Memory-cache key: a custom model is not cached at all without one. */
class SpoofImageKeyer @Inject constructor() : Keyer<SpoofImage> {
    override fun key(data: SpoofImage, options: Options): String = "spoof:${data.seed}:${data.width}x${data.height}"
}
