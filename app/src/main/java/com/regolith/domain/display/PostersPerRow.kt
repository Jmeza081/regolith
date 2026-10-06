package com.regolith.domain.display

/**
 * How many posters the Library's walls show across the inner display (any
 * wide window), when a wall has the whole screen beside the rail: the
 * owner's choice between filling it (seven) and bigger posters (five). A
 * phone, the Fold's cover screen included, always shows three.
 *
 * Seven is the default because it is what the Fold 8's inner display showed
 * before this was a choice. Stored by [name], so the order of this enum can
 * change without moving anyone's setting.
 */
enum class PostersPerRow(val count: Int) {
    FIVE(5),
    SIX(6),
    SEVEN(7),
    ;

    val label: String get() = count.toString()

    companion object {
        val DEFAULT = SEVEN

        /** Reads a stored name back; anything unknown falls back to [DEFAULT]. */
        fun of(name: String?): PostersPerRow = entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}
