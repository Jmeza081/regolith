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

    /**
     * The pictures the Library shows: in an album's mosaic, the lightbox and
     * a story. Every format the phone decodes by itself (HEIF since Android 9,
     * AVIF since 12, and minSdk is 14). Raw (`.dng`) is not one: 20–60 MB,
     * slow to decode on a phone, and Browse still lists it as a file.
     */
    private val pictureExtensions = setOf("jpg", "jpeg", "png", "webp", "gif", "heic", "heif", "avif")

    fun isPicture(name: String): Boolean = extensionOf(name) in pictureExtensions
}
