package com.regolith.domain.playback

/** What the chips over the title say: "4K", "HDR", "HEVC" (design sections 01 and 10). */
data class VideoInfo(
    val width: Int,
    val height: Int,
    /** MIME type such as "video/hevc"; null when unknown. */
    val videoMimeType: String?,
    val hdr: Boolean,
    val audioMimeType: String?,
    val audioChannels: Int?,
) {
    val resolutionLabel: String
        get() = when {
            width >= 3800 || height >= 2100 -> "4K"
            height >= 1080 || width >= 1900 -> "1080p"
            height >= 720 || width >= 1200 -> "720p"
            height > 0 -> "${height}p"
            else -> ""
        }

    val codecLabel: String = codecLabelFor(videoMimeType)
    val audioLabel: String = codecLabelFor(audioMimeType) + (audioChannels?.let { " · " + channelLabel(it) } ?: "")

    /** Chips in the order the design shows them: "4K", "HDR", "HEVC". Empty strings dropped. */
    val chips: List<String>
        get() = listOfNotNull(resolutionLabel.ifEmpty { null }, if (hdr) "HDR" else null, codecLabel.ifEmpty { null })

    companion object {
        fun codecLabelFor(mime: String?): String = when (mime?.lowercase()) {
            "video/hevc", "video/x-hevc" -> "HEVC"
            "video/avc" -> "H.264"
            "video/av01" -> "AV1"
            "video/x-vnd.on2.vp9" -> "VP9"
            "video/mp4v-es" -> "MPEG-4"
            "video/mpeg2" -> "MPEG-2"
            "audio/mp4a-latm" -> "AAC"
            "audio/ac3" -> "AC-3"
            "audio/eac3", "audio/eac3-joc" -> "E-AC-3"
            "audio/vnd.dts", "audio/vnd.dts.hd" -> "DTS"
            "audio/true-hd" -> "TrueHD"
            "audio/flac" -> "FLAC"
            "audio/opus" -> "Opus"
            "audio/mpeg" -> "MP3"
            null -> ""
            else -> mime.substringAfter('/').uppercase()
        }

        fun channelLabel(channels: Int): String = when (channels) {
            1 -> "Mono"
            2 -> "Stereo"
            6 -> "5.1"
            8 -> "7.1"
            else -> "${channels}ch"
        }
    }
}
