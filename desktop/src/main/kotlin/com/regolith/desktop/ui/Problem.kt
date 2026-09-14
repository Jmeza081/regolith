package com.regolith.desktop.ui

import com.regolith.domain.smb.SmbFailure

/**
 * Something the user has to know went wrong: a sentence, and the technical
 * small print underneath it (the phone's error card has the same two lines).
 */
data class Problem(val message: String, val detail: String? = null)

/**
 * The shared [SmbFailure]s already carry user-facing sentences ("Sign-in
 * failed", "tower is out of reach"); anything else is unexpected and says so.
 */
fun Throwable.toProblem(): Problem = when (this) {
    is SmbFailure -> Problem(message ?: "The share refused the request", detail)
    else -> Problem("Something went wrong", message ?: this::class.simpleName)
}
