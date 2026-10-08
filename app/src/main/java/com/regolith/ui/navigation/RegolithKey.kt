package com.regolith.ui.navigation

import androidx.navigation3.runtime.NavKey
import com.regolith.domain.playback.ReelClip
import com.regolith.ui.library.LibraryFilter
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
    /**
     * The poster wall; [folderId] opens one collection's wall, [onDevice] lands on the device tab (still the Library tab).
     * [filter] is the chip it opens with (Videos · Moments · Images): the one lit on the wall it was opened from.
     */
    @Serializable data class Library(
        val folderId: Long? = null,
        val onDevice: Boolean = false,
        val filter: LibraryFilter = LibraryFilter.VIDEOS,
    ) : RegolithKey
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
    /**
     * [poi] opens Search with that moment already picked: a chip in Home's
     * Moments section, where a tap on a name means "show me everywhere this
     * is". It rides on the key, like the player's queue, so a Search restored
     * after the process was killed still knows what it was opened for.
     */
    @Serializable data class Search(val poi: String? = null) : RegolithKey
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
        /**
         * Opened from the mini player: the film is already loaded and playing
         * (or paused) as it was left, so the player picks it up rather than
         * loading it again, which would resume a film you had paused.
         */
        val expand: Boolean = false,
        /**
         * Opened by a tap on the film's picture (a Continue watching card,
         * the title page's Play), which flies into the player's: the film
         * loads while it is in the air and starts playing once it has landed.
         */
        val flies: Boolean = false,
        /**
         * A Moments reel instead of one film (a profile's Play moments):
         * [fileId] is then its first clip's video. Carried whole, as Play
         * all's queue is, so it survives the process being killed.
         */
        val reel: Reel? = null,
    ) : RegolithKey {
        companion object {
            /** No row has this id; it marks a key whose film came from outside the library. */
            const val EXTERNAL = -1L

            fun external(uri: String, title: String) = Player(fileId = EXTERNAL, externalUri = uri, externalTitle = title)

            /** The player on a reel of [clips], called [title] (the collection's name). */
            fun reel(title: String, clips: List<ReelClip>) =
                Player(fileId = clips.first().fileId, reel = Reel(title, clips.map { Reel.Clip(it.fileId, it.startMs, it.endMs, it.name, it.videoName) }))
        }

        /** A reel as a route carries it: [ReelClip]s, in the order they play. */
        @Serializable data class Reel(val title: String, val clips: List<Clip>) {
            @Serializable data class Clip(val fileId: Long, val startMs: Long, val endMs: Long, val name: String, val videoName: String)

            fun toClips(): List<ReelClip> = clips.map { ReelClip(it.fileId, it.startMs, it.endMs, it.name, it.videoName) }
        }
    }

    /**
     * The lightbox: the pictures of [folderId], one at a time, opened on
     * [pictureId]. In the order the screen it was opened from showed them:
     * an album's Images tab's, or the wall's own for a picture lying loose
     * beside albums ([onWall]). The order is the settings', so it survives
     * the process being killed as the key does.
     */
    @Serializable data class Lightbox(val folderId: Long, val pictureId: Long, val onWall: Boolean = false) : RegolithKey

    /**
     * The poster editor: pick a frame of [fileId], frame it in a 2:3 box, and
     * save it as poster.jpg in the film's folder. Opened from the player,
     * starting on the frame the player was paused at ([positionMs]).
     */
    @Serializable data class PosterEditor(val fileId: Long, val positionMs: Long) : RegolithKey

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
