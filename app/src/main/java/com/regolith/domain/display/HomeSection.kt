package com.regolith.domain.display

/**
 * Home's sections, which the owner puts in any order with Home's Arrange
 * sheet. [entries] is the default order: what you were watching, what
 * arrived, what is on the phone, then the moment names, which are a way into
 * the library rather than a shelf of things in it.
 *
 * Stored by [name] ([store], [orderOf]), so this enum's own order, the
 * default, can change without moving anyone's arrangement.
 */
enum class HomeSection(val label: String) {
    CONTINUE_WATCHING("Continue watching"),
    NEWLY_ADDED("Newly added"),
    ON_THIS_DEVICE("On this device"),
    MOMENTS("Moments"),
    ;

    companion object {
        /**
         * The order kept in [stored], its names joined by commas; the
         * default when nothing is kept. A name it no longer knows is dropped
         * and a repeat counts once. A section the kept order leaves out, as
         * one added in a later version would be, comes after the rest, in
         * its default place among any others left out.
         */
        fun orderOf(stored: String?): List<HomeSection> {
            val kept = stored.orEmpty().split(',').mapNotNull { name -> entries.firstOrNull { it.name == name.trim() } }.distinct()
            return kept + entries.filter { it !in kept }
        }

        /** [order] as [orderOf] reads it back. */
        fun store(order: List<HomeSection>): String = order.joinToString(",") { it.name }
    }
}
