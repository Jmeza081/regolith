package com.regolith.data.transfer

import android.content.Context
import android.util.Size
import androidx.core.net.toUri
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DataSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.key.Keyer
import coil3.request.Options
import coil3.size.pxOrElse
import com.regolith.domain.transfer.UploadThumb
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Teaches Coil what an [UploadThumb] is: the picture the PHONE keeps for a
 * picked file, fetched with `ContentResolver.loadThumbnail`.
 *
 * The phone's own thumbnail rather than a decode of the file, because the
 * file may be a 600 MB video: the gallery already has a frame for it, and a
 * photo's thumbnail is already small. It needs the read grant the upload
 * holds, which is why a finished upload keeps its grant until its row is
 * cleared. A file with no thumbnail (a PDF) fails here, and the row draws
 * its glyph instead.
 */
class UploadThumbFetcher(
    private val thumb: UploadThumb,
    private val options: Options,
    private val context: Context,
) : Fetcher {
    override suspend fun fetch(): FetchResult = withContext(Dispatchers.IO) {
        val width = options.size.width.pxOrElse { DEFAULT_WIDTH }
        val height = options.size.height.pxOrElse { DEFAULT_WIDTH * 9 / 16 }
        val bitmap = context.contentResolver.loadThumbnail(thumb.uri.toUri(), Size(width, height), null)
        ImageFetchResult(image = bitmap.asImage(), isSampled = true, dataSource = DataSource.DISK)
    }

    class Factory @Inject constructor(@ApplicationContext private val context: Context) : Fetcher.Factory<UploadThumb> {
        override fun create(data: UploadThumb, options: Options, imageLoader: ImageLoader): Fetcher = UploadThumbFetcher(data, options, context)
    }

    private companion object {
        const val DEFAULT_WIDTH = 320
    }
}

/** Memory-cache key. Without a keyer Coil would not cache a custom model at all. */
class UploadThumbKeyer @Inject constructor() : Keyer<UploadThumb> {
    override fun key(data: UploadThumb, options: Options): String = "upload-thumb:${data.uri}"
}
