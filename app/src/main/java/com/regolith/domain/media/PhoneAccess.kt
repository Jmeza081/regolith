package com.regolith.domain.media

/**
 * How much of the phone's own video Regolith has been allowed to see.
 *
 * Android 14+ answers a request for videos with one of three things, and
 * the second is easy to miss: "Select videos" grants only the ones the
 * person picked, and new videos stay invisible until they pick again. The
 * UI says so rather than looking like a library that stopped updating.
 */
enum class PhoneAccess {
    /** Never asked, or refused. */
    NONE,

    /** "Select videos": only what was picked (READ_MEDIA_VISUAL_USER_SELECTED). */
    PARTIAL,

    /** "Allow": every video on the phone (READ_MEDIA_VIDEO). */
    FULL,
}
