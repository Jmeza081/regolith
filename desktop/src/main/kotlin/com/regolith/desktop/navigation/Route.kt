package com.regolith.desktop.navigation

import com.regolith.desktop.data.Connection
import com.regolith.domain.smb.SmbEntry

/**
 * Where the window is. The app keeps a back stack of these as a plain list,
 * the same idea as the phone's Navigation 3 keys: push to go forward, pop to
 * go back, and the top entry decides what is drawn. Web analogy: a history
 * stack you own instead of the browser's.
 */
sealed interface Route {
    /** Pick or add a server and connect. The stack's root. */
    data object Servers : Route

    /** One folder of a share; [folder] is `""` at the share root. Each subfolder is its own entry, so Back goes up. */
    data class Browse(val connection: Connection, val folder: String = "") : Route

    /** One film open for chapter editing. */
    data class Editor(val connection: Connection, val folder: String, val video: SmbEntry) : Route
}
