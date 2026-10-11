package com.regolith.data.spoof

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.core.graphics.createBitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import coil3.decode.DataSource
import coil3.fetch.SourceFetchResult
import com.regolith.data.artwork.DeckPainter
import com.regolith.domain.artwork.ArtworkKind
import com.regolith.domain.artwork.ArtworkOwner
import com.regolith.domain.artwork.ArtworkRequest
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.IOException
import kotlin.math.abs

/**
 * Spoof mode's deck, drawn for real from stand-in photos already on disk,
 * so nothing here reaches the internet: a folder whose picture is a deck
 * gets one, any other folder its photo.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SpoofDeckFetcherTest {
    @get:Rule val folder = TemporaryFolder()

    private val photos by lazy { SpoofPhotos(folder.newFolder("spoof")) }

    private val poster = ArtworkRequest(ArtworkOwner.Folder(3), ArtworkKind.POSTER)

    /** A stand-in photo of one colour, as if it had been fetched already. */
    private fun photo(seed: String, width: Int, height: Int, color: Int): SpoofImage {
        val image = SpoofImage(seed, width, height)
        val bitmap = createBitmap(width, height).apply { eraseColor(color) }
        photos.file(image).outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        return image
    }

    /** Wide red cards about a tall green one, which suits a poster best and so goes in front. */
    private fun deck(request: ArtworkRequest) = SpoofDeck(
        request = request,
        photo = photo("photo", 200, 300, Color.YELLOW),
        cards = listOf(
            photo("c0", 640, 360, Color.RED),
            photo("c1", 360, 640, Color.GREEN),
            photo("c2", 640, 360, Color.RED),
        ),
    )

    private suspend fun fetch(deck: SpoofDeck, isDeck: suspend (ArtworkRequest) -> Boolean): Pair<SourceFetchResult, Bitmap> {
        val result = SpoofDeckFetcher(deck, photos, isDeck).fetch() as SourceFetchResult
        return result to BitmapFactory.decodeFile(result.source.file().toString())
    }

    private fun near(expected: Int, actual: Int) =
        abs(Color.red(expected) - Color.red(actual)) < 24 && abs(Color.green(expected) - Color.green(actual)) < 24 &&
            abs(Color.blue(expected) - Color.blue(actual)) < 24

    @Test
    fun `a folder whose picture is a deck gets a deck of stand-ins, at the poster's size`() = runTest {
        val (result, picture) = fetch(deck(poster)) { true }
        assertEquals("image/jpeg", result.mimeType)
        assertEquals(500 to 750, picture.width to picture.height)
        // The front card's middle, where a 2:3 card's would be: the tall one.
        val middle = picture.getPixel(250, 398)
        assertTrue(Integer.toHexString(middle), near(Color.GREEN, middle))
    }

    @Test
    fun `any other folder gets its photo`() = runTest {
        val deck = deck(poster)
        val (result, picture) = fetch(deck) { false }
        assertEquals(photos.file(deck.photo).path, result.source.file().toString())
        assertEquals(200 to 300, picture.width to picture.height)
    }

    @Test
    fun `when the share cannot say, a folder gets its photo rather than nothing`() = runTest {
        val deck = deck(poster)
        val (result, _) = fetch(deck) { throw IOException("share unreachable") }
        assertEquals(photos.file(deck.photo).path, result.source.file().toString())
    }

    @Test
    fun `laid loose, the deck is a page's width and clear round the hand`() = runTest {
        val (result, picture) = fetch(deck(poster.copy(loose = true))) { true }
        assertEquals("image/webp", result.mimeType)
        assertEquals(DeckPainter.LOOSE_WIDTH, picture.width)
        assertEquals("the corners are clear", 0, Color.alpha(picture.getPixel(2, 2)))
    }

    @Test
    fun `a deck is drawn once and kept, each kind on its own`() = runTest {
        val (first, _) = fetch(deck(poster)) { true }
        val (again, _) = fetch(deck(poster)) { true }
        assertEquals(DataSource.NETWORK, first.dataSource)
        assertEquals(DataSource.DISK, again.dataSource)
        assertEquals(first.source.file(), again.source.file())
        val (thumb, picture) = fetch(deck(poster.copy(kind = ArtworkKind.THUMB))) { true }
        assertTrue(thumb.source.file() != first.source.file())
        assertEquals(320 to 180, picture.width to picture.height)
    }

    @Test
    fun `a deck dealt from other photos is drawn again, not read from before`() = runTest {
        val (first, _) = fetch(deck(poster)) { true }
        val other = deck(poster).copy(cards = listOf(photo("c9", 360, 640, Color.BLUE)))
        val (again, picture) = fetch(other) { true }
        assertEquals(DataSource.NETWORK, again.dataSource)
        assertTrue(again.source.file() != first.source.file())
        assertTrue(near(Color.BLUE, picture.getPixel(250, 398)))
    }
}
