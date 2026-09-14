package com.regolith.desktop.ui

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Keeps unsaved chapters from being dropped by leaving.
 *
 * Back and the window's close button both go through [request]. While the
 * editor reports [unsaved] changes, the request is held in [pending] and the
 * editor asks Save / Discard / Keep editing; otherwise it goes ahead at once.
 * One object for both exits, so a new way out cannot forget to ask.
 * Web analogy: a `beforeunload` handler that also guards in-app navigation.
 */
@Stable
class LeaveGuard {
    /** Set by the editor while its draft differs from the file on the share. */
    var unsaved by mutableStateOf(false)

    /** The leave that is waiting on the user's answer, or null. */
    var pending by mutableStateOf<(() -> Unit)?>(null)

    fun request(leave: () -> Unit) {
        if (unsaved) pending = leave else leave()
    }
}
