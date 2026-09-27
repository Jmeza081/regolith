package com.regolith.domain.media

/**
 * Which files count as video. Decided by extension only: the scan must not
 * open files to sniff them (a 1,284-file share would take minutes).
 */
object MediaFileTypes {
    private val videoExtensions = setOf(
        "mkv", "mp4", "m4v", "mov", "avi", "webm", "ts", "m2ts", "mts", "wmv", "flv", "mpg", "mpeg", "3gp",
    )

    fun extensionOf(name: String): String = name.substringAfterLast('.', "").lowercase()

    fun isVideo(name: String): Boolean = extensionOf(name) in videoExtensions

    /** What a phone camera saves stills as, HEIF and raw included. Only used to word an upload ("3 photos"). */
    private val photoExtensions = setOf("jpg", "jpeg", "png", "heic", "heif", "webp", "gif", "dng", "avif")

    fun isPhoto(name: String): Boolean = extensionOf(name) in photoExtensions
}
