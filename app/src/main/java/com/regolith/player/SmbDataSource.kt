package com.regolith.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import com.regolith.domain.smb.SeekableByteSource
import com.regolith.domain.smb.SmbFailure
import com.regolith.domain.smb.SmbGateway
import java.io.IOException
import javax.inject.Inject

/**
 * Feeds ExoPlayer bytes straight off the share through [SeekableByteSource].
 * A DataSource is ExoPlayer's "fetch a byte range" abstraction: it opens at
 * a position, the player reads, seeking means close + open elsewhere.
 *
 * Contract (verified by Media3's DataSourceContractTest in the unit tests):
 * honor `dataSpec.position` and `dataSpec.length`, return
 * [C.RESULT_END_OF_INPUT] at the end, throw POSITION_OUT_OF_RANGE past it.
 */
@UnstableApi
class SmbDataSource(
    private val resolver: MediaResolver,
    private val gateway: SmbGateway,
) : BaseDataSource(/* isNetwork = */ true) {

    private var uri: Uri? = null
    private var source: SeekableByteSource? = null
    private var position = 0L
    private var bytesRemaining = 0L
    private var opened = false

    @Throws(IOException::class)
    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)

        val media = resolver.resolve(dataSpec.uri)
            ?: throw DataSourceException("Unknown media ${dataSpec.uri}", PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND)

        val src = try {
            gateway.open(media.host, media.credentials, media.share, media.relPath)
        } catch (e: SmbFailure) {
            throw DataSourceException(e.message ?: "SMB open failed", e, e.toErrorCode())
        }
        source = src

        val size = src.size
        if (dataSpec.position > size) {
            src.close()
            source = null
            throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
        }
        position = dataSpec.position
        // Like FileDataSource: report what was asked for, not what exists. A
        // request running past the end is answered by read() returning
        // END_OF_INPUT early, which is what the contract test expects.
        bytesRemaining = if (dataSpec.length == C.LENGTH_UNSET.toLong()) size - position else dataSpec.length

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
        } catch (e: SmbFailure) {
            throw DataSourceException(e.message ?: "SMB read failed", e, e.toErrorCode())
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

    private fun SmbFailure.toErrorCode(): Int = when (this) {
        is SmbFailure.AuthFailed -> PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
        is SmbFailure.Forbidden -> PlaybackException.ERROR_CODE_IO_NO_PERMISSION
        is SmbFailure.Unreachable -> PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
        is SmbFailure.NotFound -> PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND
        is SmbFailure.Other -> PlaybackException.ERROR_CODE_IO_UNSPECIFIED
    }

    /** ExoPlayer asks the factory for a fresh DataSource per load. */
    class Factory @Inject constructor(
        private val resolver: MediaUriResolver,
        private val gateway: SmbGateway,
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource = SmbDataSource(resolver, gateway)
    }
}
