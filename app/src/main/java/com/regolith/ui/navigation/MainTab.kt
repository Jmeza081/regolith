package com.regolith.ui.navigation

import androidx.annotation.DrawableRes
import com.regolith.R

/**
 * The four destinations in the floating nav pill (design section 01,
 * "Type & nav"). Order here is display order. Icons are the design's own
 * glyphs (`res/drawable/rg_ic_*`, generated from its SVG paths) — with one
 * deliberate exception: [SETTINGS] wears a hex nut where the design draws a
 * sun, because a sun on a tab bar reads as brightness. See
 * `rg_ic_settings.xml` and the decision log.
 */
enum class MainTab(
    val label: String,
    @param:DrawableRes val icon: Int,
    val key: RegolithKey,
    /** Stable id for tests and argent, like a data-testid. */
    val testTag: String,
) {
    HOME("Home", R.drawable.rg_ic_home, RegolithKey.Home, "nav_home"),
    LIBRARY("Library", R.drawable.rg_ic_library, RegolithKey.Library(), "nav_library"),
    BROWSE("Browse", R.drawable.rg_ic_browse, RegolithKey.Browse(), "nav_browse"),
    SETTINGS("Settings", R.drawable.rg_ic_settings, RegolithKey.Settings, "nav_settings");

    companion object {
        /**
         * Which tab a key belongs to, or null for pushed screens (Player,
         * TitleDetail, Add Server) where the pill is hidden.
         * `Browse(folderId)` and `Library(folderId)` at any depth are still their tabs.
         */
        fun forKey(key: RegolithKey?): MainTab? = when (key) {
            RegolithKey.Home -> HOME
            is RegolithKey.Library -> LIBRARY
            is RegolithKey.Browse -> BROWSE
            RegolithKey.Settings -> SETTINGS
            else -> null
        }
    }
}
