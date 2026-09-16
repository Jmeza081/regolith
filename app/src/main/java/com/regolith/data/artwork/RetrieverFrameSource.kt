package com.regolith.data.artwork

import android.graphics.Bitmap
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import android.util.Log
import com.regolith.domain.smb.BufferedByteSource
import com.regolith.domain.smb.SeekableByteSource
import com.regolith.domain.smb.SmbCredentials
import com.regolith.domain.smb.SmbGateway
import com.regolith.domain.smb.SmbHost
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [FrameSource] on Android's `MediaMetadataRetriever`, reading the file
 * straight off the share through a [MediaDataSource] adapter.
 *
 * Why this and not Media3's `FrameExtractor`: the retriever takes a
 * random-access byte source directly, hands back a `Bitmap` with no GL
 * pipeline, and `OPTION_CLOSEST_SYNC` decodes exactly one key frame per
 * request, which is the cheapest possible seek over SMB. `FrameExtractor`
 * needs `media3-effect` and an OpenGL context per extraction. It stays
 * one class away behind [FrameSource] if the platform extractors reject
 * a container the player can handle.
 */
class RetrieverFrameSource private constructor(
    private val source: SeekableByteSource,
    private val retriever: MediaMetadataRetriever,
    private val stats: ByteSourceMediaDataSource,
) : FrameSource {

    override val durationMs: Long? = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.takeIf { it > 0 }
    override val width: Int? = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()?.takeIf { it > 0 }
    override val height: Int? = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()?.takeIf { it > 0 }
    override val rotationDegrees: Int? = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull()

    override fun embeddedPicture(): ByteArray? = runCatching { retriever.embeddedPicture }.getOrNull()

    override fun frameAt(positionMs: Long, maxWidth: Int, maxHeight: Int): Bitmap? = try {
        val reads0 = stats.reads
        val bytes0 = stats.bytes
        val nanos0 = stats.readNanos
        val started = System.nanoTime()
        retriever.getScaledFrameAtTime(positionMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, maxWidth, maxHeight).also {
            Log.d(TAG, "frame at ${positionMs}ms: ${stats.reads - reads0} reads, ${(stats.bytes - bytes0) / 1024} KB, ${(stats.readNanos - nanos0) / 1_000_000} ms reading, ${(System.nanoTime() - started) / 1_000_000} ms total")
        }
    } catch (e: RuntimeException) {
        Log.w(TAG, "frame at ${positionMs}ms failed: ${e.message}")
        null
    }

    override fun close() {
        runCatching { retriever.release() }
        runCatching { source.close() }
    }

    /**
     * The retriever's view of a file. Its native side calls [readAt] from a
     * decoder thread and expects a full read or end of file, so short reads
     * from the share are looped here.
     */
    private class ByteSourceMediaDataSource(private val source: SeekableByteSource) : MediaDataSource() {
        @Volatile var reads = 0L
        @Volatile var bytes = 0L
        @Volatile var readNanos = 0L

        override fun getSize(): Long = source.size

        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            if (size == 0) return 0
            if (position >= source.size) return -1
            reads++
            bytes += size
            val started = System.nanoTime()
            var done = 0
            while (done < size) {
                val n = source.readAt(position + done, buffer, offset + done, size - done)
                if (n <= 0) break
                done += n
            }
            readNanos += System.nanoTime() - started
            return if (done == 0) -1 else done
        }

        override fun close() = Unit // the FrameSource owns the byte source
    }

    @Singleton
    class Factory @Inject constructor(private val gateway: SmbGateway) : FrameSourceFactory {
        override fun open(host: SmbHost, credentials: SmbCredentials, share: String, relPath: String): FrameSource {
            // Read-ahead matters here as much as in the player: the platform
            // extractor reads container headers in small pieces too. Several
            // smaller blocks, because a frame grab hops between the sample
            // tables (end of file) and the frame bytes (anywhere).
            val source = BufferedByteSource(gateway.open(host, credentials, share, relPath), blockSize = FRAME_BLOCK_SIZE, blockCount = FRAME_BLOCK_COUNT)
            val retriever = MediaMetadataRetriever()
            val dataSource = ByteSourceMediaDataSource(source)
            val started = System.nanoTime()
            try {
                retriever.setDataSource(dataSource)
            } catch (e: RuntimeException) {
                retriever.release()
                source.close()
                throw e
            }
            Log.d(TAG, "open $relPath: ${dataSource.reads} reads, ${dataSource.bytes / 1024} KB, ${(System.nanoTime() - started) / 1_000_000} ms")
            return RetrieverFrameSource(source, retriever, dataSource)
        }

        override fun openLocal(file: java.io.File): FrameSource {
            // Same class, different byte source: the retriever cannot tell,
            // and neither can anything above it.
            val source = FileByteSource(file)
            val retriever = MediaMetadataRetriever()
            val dataSource = ByteSourceMediaDataSource(source)
            try {
                retriever.setDataSource(dataSource)
            } catch (e: RuntimeException) {
                retriever.release()
                source.close()
                throw e
            }
            return RetrieverFrameSource(source, retriever, dataSource)
        }
    }

    /** A file on this device, read the way the share is read. No buffering: the page cache is the buffer. */
    private class FileByteSource(file: java.io.File) : SeekableByteSource {
        private val handle = java.io.RandomAccessFile(file, "r")
        override val size: Long = handle.length()

        override fun readAt(offset: Long, dst: ByteArray, dstOffset: Int, length: Int): Int = synchronized(handle) {
            if (offset >= size) return -1
            handle.seek(offset)
            handle.read(dst, dstOffset, length)
        }

        override fun close() {
            runCatching { handle.close() }
        }
    }

    private companion object {
        const val TAG = "Regolith/Frames"
        const val FRAME_BLOCK_SIZE = 256 * 1024
        const val FRAME_BLOCK_COUNT = 8 // 2 MiB resident
    }
}
