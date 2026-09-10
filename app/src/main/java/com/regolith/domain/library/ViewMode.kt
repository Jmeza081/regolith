package com.regolith.domain.library

/**
 * How a list of media is laid out. Library and Browse each own one of
 * these and remember it across launches ([com.regolith.data.prefs.AppPreferences]).
 *
 * Web analogy: the grid/list switch on a file manager, saved to
 * localStorage so the choice survives a reload.
 */
enum class ViewMode {
    /** Tiles in a grid: posters on Library, 16:9 frames on Browse. */
    GRID,

    /** One row per item inside a card: a small thumbnail, name, meta. */
    ROWS,

    ;

    fun toggled(): ViewMode = if (this == GRID) ROWS else GRID
}
