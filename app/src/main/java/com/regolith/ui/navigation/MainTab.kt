package com.regolith.ui.navigation

import androidx.annotation.DrawableRes
import com.composables.icons.lucide.R as LucideR

/**
 * The four destinations in the floating nav pill (design section 01,
 * "Type & nav"). Order here is display order. Icons are Lucide vector
 * drawables from the `icons-lucide-android` artifact.
 */
enum class MainTab(
    val label: String,
    @param:DrawableRes val icon: Int,
    val key: RegolithKey,
    /** Stable id for tests and argent, like a data-testid. */
    val testTag: String,
) {
    HOME("Home", LucideR.drawable.lucide_ic_house, RegolithKey.Home, "nav_home"),
    LIBRARY("Library", LucideR.drawable.lucide_ic_library_big, RegolithKey.Library, "nav_library"),
    BROWSE("Browse", LucideR.drawable.lucide_ic_folder_open, RegolithKey.Browse(), "nav_browse"),
    SETTINGS("Settings", LucideR.drawable.lucide_ic_settings, RegolithKey.Settings, "nav_settings");

    companion object {
        /**
         * Which tab a key belongs to, or null for pushed screens (Player,
         * TitleDetail, Add Server) where the pill is hidden.
         * `Browse(folderId)` at any depth is still the Browse tab.
         */
        fun forKey(key: RegolithKey?): MainTab? = when (key) {
            RegolithKey.Home -> HOME
            RegolithKey.Library -> LIBRARY
            is RegolithKey.Browse -> BROWSE
            RegolithKey.Settings -> SETTINGS
            else -> null
        }
    }
}
