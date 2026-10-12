package com.regolith.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import com.regolith.domain.smb.SeekableByteSource
import java.io.IOException

/**
 * Feeds Media3 the bytes of whatever [openSource] returns, a fresh source per
 * load: a [com.regolith.domain.smb.SplicedByteSource] that turns a file with
 * no index into one that starts in its middle, for a frame grab
 * ([Media3Frames.frameFromBytes]). The same contract as [SmbDataSource],
 * without its lookup of the file by URI: the bytes are decided before the
 * extractor is built, so the URI is only a name.
 */
@UnstableApi
class ByteSourceDataSource(private val openSource: () -> SeekableByteSource) : BaseDataSource(/* isNetwork = */ true) {
    private var uri: Uri? = null
    private var source: SeekableByteSource? = null
    private var position = 0L
    private var bytesRemaining = 0L
    private var opened = false

    @Throws(IOException::class)
    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        val src = try {
            openSource()
        } catch (e: Exception) {
            throw DataSourceException(e.message ?: "open failed", e, PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
        }
        source = src
        if (dataSpec.position > src.size) {
            throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
        }
        position = dataSpec.position
        bytesRemaining = if (dataSpec.length == C.LENGTH_UNSET.toLong()) src.size - position else dataSpec.length
        opened = true
        transferStarted(dataSpec)
        return bytesRemaining
    }

    @Throws(IOException::class)
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
        val src = source ?: throw IOException("read before open")
        val n = try {
            src.readAt(position, buffer, offset, minOf(length.toLong(), bytesRemaining).toInt())
        } catch (e: Exception) {
            throw DataSourceException(e.message ?: "read failed", e, PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
        }
        if (n <= 0) return C.RESULT_END_OF_INPUT
        position += n
        bytesRemaining -= n
        bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        uri = null
        try {
            source?.close()
        } finally {
            source = null
            if (opened) {
                opened = false
                transferEnded()
            }
        }
    }
}
