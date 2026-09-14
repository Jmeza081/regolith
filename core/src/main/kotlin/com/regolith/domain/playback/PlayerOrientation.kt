package com.regolith.domain.playback

/**
 * What the player asks the screen to do while it is open
 * (Settings sheet › Rotation).
 *
 * [AUTO] is what the player has always done: follow the phone. The two
 * locks exist because a film is not a document — lying on your side to
 * watch something, or holding a phone that keeps flipping under you, are
 * both things the sensor gets wrong more often than it gets right.
 *
 * Remembered across films: a lock you have to set every time is not a lock.
 */
enum class PlayerOrientation(val label: String) {
    AUTO("Auto"),
    PORTRAIT("Portrait"),
    LANDSCAPE("Landscape"),
}
