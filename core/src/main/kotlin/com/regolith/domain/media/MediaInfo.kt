package com.regolith.domain.media

import com.regolith.domain.playback.VideoInfo

/**
 * What a probe of the container reports (design section 09: "H.264 ·
 * 1920×1080 · 24 fps", "AC-3 · 5.1 · 48 kHz"). Stored on the file row so
 * Browse chips and Title Detail never probe twice.
 */
data class MediaInfo(
    val durationMs: Long?,
    val width: Int?,
    val height: Int?,
    val frameRate: Float?,
    /** Sample MIME type, e.g. "video/avc". */
    val videoMimeType: String?,
    val hdr: Boolean,
    val audioMimeType: String?,
    val audioChannels: Int?,
    val audioSampleRate: Int?,
) {
    /** "H.264 · 1920×1080 · 24 fps"; parts that are unknown are left out. */
    val videoLine: String
        get() = listOfNotNull(
            VideoInfo.codecLabelFor(videoMimeType).ifEmpty { null },
            if (width != null && height != null && width > 0) "${width}×${height}" else null,
            frameRate?.takeIf { it > 0 }?.let { formatFps(it) },
        ).joinToString(" · ")

    /** "AC-3 · 5.1 · 48 kHz". */
    val audioLine: String
        get() = listOfNotNull(
            VideoInfo.codecLabelFor(audioMimeType).ifEmpty { null },
            audioChannels?.takeIf { it > 0 }?.let { VideoInfo.channelLabel(it) },
            audioSampleRate?.takeIf { it > 0 }?.let { "${it / 1000} kHz" },
        ).joinToString(" · ")

    companion object {
        fun formatFps(fps: Float): String {
            val rounded = Math.round(fps * 100) / 100f
            val text = if (rounded == Math.round(rounded).toFloat()) Math.round(rounded).toString() else rounded.toString()
            return "$text fps"
        }
    }
}
