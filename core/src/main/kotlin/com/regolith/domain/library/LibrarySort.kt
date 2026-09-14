package com.regolith.domain.library

/** How the poster wall is ordered (design section 05, "Sort by"). Remembered in preferences. */
enum class LibrarySort(val label: String) {
    NAME("Name, A to Z"),
    DATE_ADDED("Date added"),
    FILE_SIZE("File size"),
    RUNTIME("Runtime"),
    RESOLUTION("Resolution"),
}
