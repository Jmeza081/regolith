package com.regolith.domain.display

/**
 * How long the navigation waits after the last touch before it slides away:
 * the pill on a phone (the Fold's outer display) and the rail on a wide
 * window (its inner one). One clock for both, because they are the same
 * control drawn two ways.
 *
 * Only matters while Settings › Display › Auto-hide the navigation is on;
 * turning that off is how you say "never". It was a fixed three seconds,
 * borrowed from the player's chrome, and that turned out to be too quick
 * for a screen you READ rather than watch, so the shortest choice is now
 * five and the default is ten.
 *
 * Stored by [name], so the order of this enum can change without moving
 * anyone's setting.
 */
enum class NavHideAfter(val label: String, val idleMs: Long) {
    FIVE_SECONDS("5 s", 5_000L),
    TEN_SECONDS("10 s", 10_000L),
    THIRTY_SECONDS("30 s", 30_000L),
    ;

    companion object {
        val DEFAULT = TEN_SECONDS

        /** Reads a stored name back; anything unknown falls back to [DEFAULT]. */
        fun of(name: String?): NavHideAfter = entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}
