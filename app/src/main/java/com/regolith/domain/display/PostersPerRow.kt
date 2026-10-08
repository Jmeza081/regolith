package com.regolith.domain.display

/**
 * How many posters the poster walls show across the inner display (any
 * wide window), when a wall has the whole screen beside the rail: the
 * owner's choice between filling it (seven) and the biggest posters (four).
 * A phone, the Fold's cover screen included, always shows three.
 *
 * Chosen in Settings › Display, or by pinching a wall a step at a time
 * ([pinchStep]); both write this one setting, which the Library's walls and
 * Home's all follow.
 *
 * Seven is the default because it is what the Fold 8's inner display showed
 * before this was a choice. Stored by [name], so the order of this enum can
 * change without moving anyone's setting.
 */
enum class PostersPerRow(val count: Int) {
    FOUR(4),
    FIVE(5),
    SIX(6),
    SEVEN(7),
    ;

    val label: String get() = count.toString()

    companion object {
        val DEFAULT = SEVEN

        /** Reads a stored name back; anything unknown falls back to [DEFAULT]. */
        fun of(name: String?): PostersPerRow = entries.firstOrNull { it.name == name } ?: DEFAULT

        /** The setting for [count] posters across, held to the range there is: what a pinch keeps. */
        fun ofCount(count: Int): PostersPerRow =
            entries.firstOrNull { it.count == count } ?: if (count < FOUR.count) FOUR else SEVEN
    }
}

/**
 * Where one step of a pinch on a poster wall lands: the posters across after
 * it, or null past either end of what can be reached, which the wall answers
 * with a bump instead of a step.
 *
 * It steps from what the wall SHOWS ([shown]), not from the setting: a window
 * too narrow for seven holds the wall at fewer (`wallColumns`), and stepping
 * from the setting there would spend the first pinches changing nothing on
 * screen. [most] is how many fit across at all.
 *
 * @param fewer true when the fingers spread apart: bigger posters, fewer across.
 * @param fewest and [top] are the range the setting has: four to seven
 *   posters, or an album's mosaic's own ([PicturesAcross]).
 */
fun pinchStep(
    shown: Int,
    fewer: Boolean,
    most: Int,
    fewest: Int = PostersPerRow.FOUR.count,
    top: Int = PostersPerRow.SEVEN.count,
): Int? {
    val next = if (fewer) shown - 1 else shown + 1
    return next.takeIf { it >= fewest && it <= minOf(top, most) }
}
