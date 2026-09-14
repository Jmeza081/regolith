package com.regolith.desktop.data

import coil3.PlatformContext
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import com.regolith.domain.smb.SmbCredentials
import com.regolith.domain.smb.SmbHost
import com.regolith.testing.FakeSmbGateway
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

class ShareImageTest {
    private val connection = Connection(SmbHost("tower"), SmbCredentials.Guest, "media")
    private val context = PlatformContext.INSTANCE

    private fun png(width: Int, height: Int): ByteArray =
        ByteArrayOutputStream().also { ImageIO.write(BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), "png", it) }.toByteArray()

    @Test
    fun `an image on the share is read and decoded`() = runBlocking {
        val bytes = png(40, 60)
        val fake = FakeSmbGateway().apply { addFile("media", "Films/Heat.1995.jpg", bytes) }
        val request = ImageRequest.Builder(context).data(ShareImage(fake, connection, "Films/Heat.1995.jpg", bytes.size.toLong())).build()
        val result = buildShareImageLoader(context).execute(request)
        assertTrue("expected a decoded image, got $result", result is SuccessResult)
        val image = (result as SuccessResult).image
        assertEquals(40, image.width)
        assertEquals(60, image.height)
    }

    @Test
    fun `a missing image is an error, which a row draws as the unmatched look`() = runBlocking {
        val request = ImageRequest.Builder(context).data(ShareImage(FakeSmbGateway(), connection, "Films/gone.jpg", 10)).build()
        val result = buildShareImageLoader(context).execute(request)
        assertTrue("expected an error, got $result", result is ErrorResult)
    }
}
