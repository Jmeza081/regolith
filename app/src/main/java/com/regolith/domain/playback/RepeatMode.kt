package com.regolith.domain.playback

/**
 * What happens when a file reaches its end (the player's repeat button).
 *
 * [OFF] leaves it to "Keep playing": the next file in the folder, or
 * nothing. [ALL] wraps the running order, so the last file is followed by
 * the first. [ONE] loops the file itself and never moves on.
 *
 * Not the same thing as the A–B loop, which repeats a SPAN you marked
 * inside one file. This repeats whole files.
 *
 * Remembered across films, the way the rotation lock is: a mode you have to
 * set for every file is not a mode.
 */
enum class RepeatMode(val label: String) {
    OFF("Off"),
    ALL("Repeat all"),
    ONE("Repeat one");

    /** Off -> all -> one -> off, for the one button that cycles them. */
    fun next(): RepeatMode = entries[(ordinal + 1) % entries.size]
}
