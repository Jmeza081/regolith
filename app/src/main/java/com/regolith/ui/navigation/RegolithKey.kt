package com.regolith.ui.navigation

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

/**
 * Every place the app can be. Navigation 3 has no XML graph: the back stack
 * is a plain list of these keys, and [RegolithNavGraph] maps each key to a
 * screen. Web analogy: the route table, typed.
 *
 * Keys must be `@Serializable` so the back stack survives process death.
 * Arguments are constructor parameters, never strings in a path.
 */
@Serializable
sealed interface RegolithKey : NavKey {

    /** First run only. Ends on "Find my server". */
    @Serializable data object Onboarding : RegolithKey

    // --- The four tabs. Exactly one is at the top of the stack when the
    // nav pill is visible.
    @Serializable data object Home : RegolithKey
    /** The poster wall; [folderId] opens one collection's wall, [onDevice] lands on the device tab (still the Library tab). */
    @Serializable data class Library(val folderId: Long? = null, val onDevice: Boolean = false) : RegolithKey
    @Serializable data class Browse(val folderId: Long? = null) : RegolithKey
    @Serializable data object Settings : RegolithKey

    // --- Pushed screens.
    @Serializable data object Search : RegolithKey
    @Serializable data class TitleDetail(val fileId: Long) : RegolithKey
    @Serializable data class Player(val fileId: Long, val startMs: Long? = null) : RegolithKey

    /** Add Source Server flow (design section 03). Phase 1 fills these in. */
    @Serializable sealed interface AddServer : RegolithKey {
        @Serializable data object Search : AddServer
        @Serializable data object Manual : AddServer
        @Serializable data class Connecting(val serverId: Long) : AddServer
        @Serializable data class Shares(val serverId: Long) : AddServer
        @Serializable data class Scanning(val serverId: Long) : AddServer
    }
}
