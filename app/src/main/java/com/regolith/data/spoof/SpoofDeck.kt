package com.regolith.data.spoof

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.key.Keyer
import coil3.request.Options
import com.regolith.data.artwork.ArtworkRepository
import com.regolith.data.artwork.ArtworkStore
import com.regolith.data.artwork.DeckPainter
import com.regolith.domain.artwork.ArtworkRequest
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import java.io.File
import java.io.IOException
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException

/**
 * A folder's picture while spoof mode is on ([Spoof.standIn]): its stand-in
 * [photo], as every other picture has, unless the folder's real picture is
 * a deck ([com.regolith.domain.artwork.ArtworkSource.DECK]). Then it is a
 * deck too, dealt from [cards]: stand-in photos at the shapes
 * [com.regolith.domain.spoof.SpoofNames.deckShapes] picks for the folder.
 * So a demo shows a deck wherever the library has one, and nothing of
 * what is in it.
 *
 * [request] is the real picture's: it says whether that is a deck, and
 * whether this one is wanted laid loose ([ArtworkRequest.loose]).
 */
data class SpoofDeck(val request: ArtworkRequest, val photo: SpoofImage, val cards: List<SpoofImage>)

/**
 * Fetches a [SpoofDeck]. [isDeck] says whether the folder's real picture is
 * a deck, making it first if need be, as a tile would with spoof mode off:
 * a folder nobody has opened yet gets its deck in a demo too, and nothing
 * of its real picture is shown. The deck is drawn by [DeckPainter], as the
 * real one is, from the stand-in photos, and kept beside them
 * ([SpoofPhotos]) for next time.
 */
class SpoofDeckFetcher(
    private val deck: SpoofDeck,
    private val photos: SpoofPhotos,
    private val isDeck: suspend (ArtworkRequest) -> Boolean,
) : Fetcher {
    override suspend fun fetch(): FetchResult {
        // A stand-in never failed for want of the share, and does not start
        // to: a folder whose picture cannot be told gets its photo.
        val dealt = try {
            isDeck(deck.request)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
        return withContext(Dispatchers.IO) {
            if (!dealt) {
                val fetched = photos.fetch(deck.photo)
                return@withContext result(photos.file(deck.photo), "image/jpeg", fetched)
            }
            val loose = deck.request.loose
            val file = File(photos.directory, "deck-${deck.photo.seed}-${if (loose) "loose.webp" else "${deck.request.kind.name.lowercase()}.jpg"}")
            val fetched = !file.exists()
            if (fetched) deal(file)
            result(file, if (loose) "image/webp" else "image/jpeg", fetched)
        }
    }

    private fun result(file: File, mimeType: String, fetched: Boolean) = SourceFetchResult(
        source = ImageSource(file.toOkioPath(), FileSystem.SYSTEM),
        mimeType = mimeType,
        dataSource = if (fetched) DataSource.NETWORK else DataSource.DISK,
    )

    /**
     * Draws the deck into [file]: boxed at its kind's size, or laid loose on
     * a transparent ground (WebP, as the real loose deck is). Every card or
     * none, so a photo that would not come is fetched again next time
     * rather than leaving a deck a card short for good.
     */
    private fun deal(file: File) {
        val faces = mutableListOf<Bitmap>()
        try {
            for (card in deck.cards) {
                photos.fetch(card)
                faces += BitmapFactory.decodeFile(photos.file(card).path) ?: throw IOException("stand-in card: not a picture")
            }
            val request = deck.request
            val picture = if (request.loose) {
                DeckPainter.paintLoose(faces, DeckPainter.LOOSE_WIDTH)
            } else {
                DeckPainter.paint(faces, request.kind.width, request.kind.height)
            }
            try {
                // Written beside, then moved into place, as a photo is.
                val partial = File.createTempFile(file.nameWithoutExtension, ".part", photos.directory)
                val format = if (request.loose) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.JPEG
                partial.outputStream().use { picture.compress(format, ArtworkStore.JPEG_QUALITY, it) }
                if (!partial.renameTo(file)) partial.delete()
            } finally {
                picture.recycle()
            }
        } finally {
            faces.forEach { it.recycle() }
        }
    }

    class Factory @Inject constructor(
        @ApplicationContext private val context: Context,
        private val artwork: ArtworkRepository,
    ) : Fetcher.Factory<SpoofDeck> {
        override fun create(data: SpoofDeck, options: Options, imageLoader: ImageLoader): Fetcher =
            SpoofDeckFetcher(data, SpoofPhotos.inCache(context), artwork::isDeck)
    }
}

/** Memory-cache key: a folder's stand-in at its kind, boxed or laid loose. */
class SpoofDeckKeyer @Inject constructor() : Keyer<SpoofDeck> {
    override fun key(data: SpoofDeck, options: Options): String =
        "spoof:deck:${data.photo.seed}:${data.request.kind.name.lowercase()}${if (data.request.loose) ":loose" else ""}"
}
