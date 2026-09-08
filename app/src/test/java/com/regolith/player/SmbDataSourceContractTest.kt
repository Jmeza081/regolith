package com.regolith.player

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.test.utils.DataSourceContractTest
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.collect.ImmutableList
import com.regolith.domain.smb.SmbCredentials
import com.regolith.domain.smb.SmbHost
import com.regolith.testing.FakeSmbGateway
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.random.Random

/**
 * Media3's own contract suite run against [SmbDataSource] over the fake
 * gateway. The fake deliberately returns short reads (7 bytes max) so a
 * DataSource that assumed one read fills the buffer would fail here.
 */
@UnstableApi
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class SmbDataSourceContractTest : DataSourceContractTest() {

    private val bytes = Random(42).nextBytes(10_000)
    private val host = SmbHost("tower")
    private val gateway = FakeSmbGateway().apply { addFile("media", "Films/clip.mp4", bytes) }
    private val resolver = MediaResolver { uri ->
        if (uri.toString().equals(FILE_URI, ignoreCase = true)) ResolvedMedia(host, SmbCredentials.Guest, "media", "Films/clip.mp4", bytes.size.toLong()) else null
    }

    override fun createDataSource(): DataSource = SmbDataSource(resolver, gateway)

    override fun getTestResources(): ImmutableList<TestResource> = ImmutableList.of(
        TestResource.Builder().setName("clip").setUri(Uri.parse(FILE_URI)).setExpectedBytes(bytes).build(),
    )

    override fun getNotFoundUri(): Uri = Uri.parse("regolith://file/999")

    private companion object {
        const val FILE_URI = "regolith://file/1"
    }
}
