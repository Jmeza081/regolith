package com.regolith.player

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.inspector.MetadataRetriever
import com.regolith.domain.media.MediaInfo
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads a file's tracks without playing it: codec, picture size, frame
 * rate, audio layout (design section 09). Media3's `MetadataRetriever`
 * runs the same extractors ExoPlayer does through the same SMB data
 * source, so what it reports is what will play. Runtime cost is the
 * container headers only: a few blocks off the share.
 */
@UnstableApi
@Singleton
class MediaProbe @Inject constructor(
    @ApplicationContext private val context: Context,
    private val smbDataSourceFactory: SmbDataSource.Factory,
    private val resolver: MediaUriResolver,
) {
    /** Throws on an unreachable share or an unreadable container. */
    suspend fun probe(fileId: Long): MediaInfo {
        // Resolved outside runInterruptible: it is a suspending lookup, and a
        // copy on this device means the probe never touches the share.
        val uri = resolver.playableUriFor(fileId)
        return runInterruptible(Dispatchers.IO) { probeUri(uri) }
    }

    private fun probeUri(uri: android.net.Uri): MediaInfo {
        val mediaSources = DefaultMediaSourceFactory(context)
            .setDataSourceFactory(DefaultDataSource.Factory(context, smbDataSourceFactory))
        return MetadataRetriever.Builder(context, MediaItem.fromUri(uri))
            .setMediaSourceFactory(mediaSources)
            .build()
            .use { retriever ->
                val groups = retriever.retrieveTrackGroups().get()
                val durationUs = retriever.retrieveDurationUs().get()
                var info = MediaInfo(
                    durationMs = durationUs.takeIf { it != C.TIME_UNSET && it > 0 }?.let { it / 1000 },
                    width = null, height = null, rotationDegrees = null, frameRate = null, videoMimeType = null, hdr = false,
                    audioMimeType = null, audioChannels = null, audioSampleRate = null,
                )
                for (i in 0 until groups.length) {
                    val group = groups[i]
                    if (group.length == 0) continue
                    val format = group.getFormat(0)
                    val mime = format.sampleMimeType ?: continue
                    if (mime.startsWith("video/") && info.videoMimeType == null) {
                        val transfer = format.colorInfo?.colorTransfer
                        info = info.copy(
                            width = format.width.takeIf { it > 0 },
                            height = format.height.takeIf { it > 0 },
                            // NO_VALUE comes back as -1; 0 and "not stated" mean the same thing here.
                            rotationDegrees = format.rotationDegrees.takeIf { it > 0 },
                            frameRate = format.frameRate.takeIf { it > 0 },
                            videoMimeType = mime,
                            hdr = transfer == C.COLOR_TRANSFER_ST2084 || transfer == C.COLOR_TRANSFER_HLG,
                        )
                    } else if (mime.startsWith("audio/") && info.audioMimeType == null) {
                        info = info.copy(
                            audioMimeType = mime,
                            audioChannels = format.channelCount.takeIf { it > 0 },
                            audioSampleRate = format.sampleRate.takeIf { it > 0 },
                        )
                    }
                }
                info
            }
    }
}
