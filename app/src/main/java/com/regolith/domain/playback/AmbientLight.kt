package com.regolith.domain.playback

/**
 * What lights the space around the picture: Settings › Display › Ambient
 * light, mirrored in the player's playback sheet.
 *
 * - [OFF]: a still glow made from the film's backdrop art, and nothing read
 *   back off the video. The player keeps its faster SurfaceView.
 * - [MIRROR]: the picture itself, shrunk to 32×18, blown up and blurred
 *   behind the film — the light the app has had since F10.
 * - [COLOR_BLEED]: one colour per zone along the picture's edges, shone
 *   outward the way an LED strip behind a TV lights the wall.
 *
 * Stored by [name], so the order of this enum can change without moving
 * anyone's setting.
 */
enum class AmbientLight(val label: String) {
    OFF("Off"),
    MIRROR("Mirror"),
    COLOR_BLEED("Color bleed"),
    ;

    /**
     * Whether this light follows the film. Both live lights read the picture
     * back off the video surface, which only a TextureView allows.
     */
    val live: Boolean get() = this != OFF

    companion object {
        val DEFAULT = MIRROR

        /**
         * Reads the stored choice back. Before Color bleed the setting was a
         * switch, so a phone that has never saved a choice by name falls back
         * to what its switch said: off stays off, and on is Mirror, the light
         * it was already seeing.
         */
        fun of(name: String?, legacyOn: Boolean? = null): AmbientLight =
            entries.firstOrNull { it.name == name } ?: if (legacyOn == false) OFF else DEFAULT
    }
}
