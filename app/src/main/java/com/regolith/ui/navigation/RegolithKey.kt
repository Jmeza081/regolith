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

    // --- The five tabs. Exactly one is at the top of the stack when the
    // nav pill is visible.
    @Serializable data object Home : RegolithKey
    /** The poster wall; [folderId] opens one collection's wall, [onDevice] lands on the device tab (still the Library tab). */
    @Serializable data class Library(val folderId: Long? = null, val onDevice: Boolean = false) : RegolithKey
    /**
     * [highlightFileId] is a file to scroll to and ring once on arrival —
     * Shorts' Locate, which answers "where does this clip actually live".
     * It rides on the KEY rather than in a ViewModel for the same reason the
     * player's queue does: a jump has to survive the process being killed
     * and restored, or coming back lands you at the top of a folder with no
     * idea which file you came for.
     */
    @Serializable data class Browse(val folderId: Long? = null, val highlightFileId: Long? = null) : RegolithKey
    /** The vertical feed: every portrait clip of a minute or less, across every enabled share. */
    @Serializable data object Shorts : RegolithKey
    @Serializable data object Settings : RegolithKey

    // --- Pushed screens.
    @Serializable data object Search : RegolithKey
    /** Home's "All": every part-watched title. */
    @Serializable data object ContinueWatching : RegolithKey
    @Serializable data class TitleDetail(val fileId: Long) : RegolithKey

    /**
     * One source server's own page: its name, the ways to reach it, and
     * what its library is doing. Settings lists servers; everything ABOUT
     * one lives here, which is also what finally gives "choose folders" a
     * home outside the Add Server flow.
     */
    @Serializable data class ServerDetail(val serverId: Long) : RegolithKey
    /**
     * [queue] is an explicit running order from Play all or Shuffle: file ids
     * in the order the wall showed them, [fileId] being the first. Empty for
     * the ordinary case of opening one file, where "next" is worked out from
     * the folder instead. It rides on the key rather than in a ViewModel so a
     * queue survives the process being killed and restored.
     */
    /**
     * The player. Normally a library file by id; [externalUri] is set instead
     * when another app handed us a film to play, in which case there is no
     * row, no artwork, no folder and no queue — just a URI and a name.
     */
    @Serializable data class Player(
        val fileId: Long,
        val startMs: Long? = null,
        val queue: List<Long> = emptyList(),
        val externalUri: String? = null,
        val externalTitle: String? = null,
    ) : RegolithKey {
        companion object {
            /** No row has this id; it marks a key whose film came from outside the library. */
            const val EXTERNAL = -1L

            fun external(uri: String, title: String) = Player(fileId = EXTERNAL, externalUri = uri, externalTitle = title)
        }
    }

    /** Add Source Server flow (design section 03). Phase 1 fills these in. */
    @Serializable sealed interface AddServer : RegolithKey {
        @Serializable data object Search : AddServer
        /** [prefill] is the address the finder picked, so the field starts filled. */
        @Serializable data class Manual(val prefill: String? = null) : AddServer
        @Serializable data class Connecting(val serverId: Long) : AddServer
        /** "Name this server": optional, between connecting and choosing shares. */
        @Serializable data class Name(val serverId: Long) : AddServer
        @Serializable data class Shares(val serverId: Long) : AddServer
        /**
         * "Choose folders": one level of one share, [relPath] `""` for its
         * top. Drilling pushes another of these, so back climbs out a level
         * at a time and Done pops them all.
         */
        @Serializable data class Folders(val shareId: Long, val relPath: String = "") : AddServer
        @Serializable data class Scanning(val serverId: Long) : AddServer
    }
}
