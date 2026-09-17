package com.regolith.ui.util

import com.regolith.domain.fileops.FileOpError
import com.regolith.domain.fileops.FileOpFailure
import com.regolith.domain.fileops.FileOpResult

/**
 * What to say when a rename, move or delete does not go through.
 *
 * Shared by Browse and Title Detail so the same failure never gets two
 * different explanations. Every sentence says what is true of the SHARE
 * now, because that is the thing the user is worried about.
 */
object FileOpMessages {

    /** One failed item, in a sentence. [verb] is the infinitive: "move", "delete". */
    fun forFailure(failure: FileOpFailure, verb: String): String = when (failure.error) {
        FileOpError.NAME_TAKEN -> "There's already something called that here."
        FileOpError.FORBIDDEN -> "The share won't allow it. It's read-only to Regolith."
        FileOpError.UNREACHABLE -> "The share dropped. Nothing was left half-done."
        FileOpError.NOT_FOUND -> "${failure.name} isn't on the share any more."
        // Names the real rules, because this fires for an EMPTY field too —
        // where "has characters a share can't take" was plainly untrue.
        FileOpError.BAD_NAME -> "A name can't be empty, end in a dot, or use \\ / : * ? \" < > |."
        FileOpError.OTHER -> "Couldn't $verb ${failure.name}."
    }

    /**
     * A finished batch, in a line. [pastTense] is "Moved" or "Deleted".
     *
     * A partial result leads with how far it got, because that is the fact
     * the user needs: each file was either done or untouched, never both.
     */
    fun forResult(result: FileOpResult, verb: String, pastTense: String, where: String? = null): String {
        val done = result.done.size
        val total = done + result.failures.size
        val subject = if (done == 1) "1 video" else "$done videos"
        val destination = where?.let { " to $it" } ?: ""
        return when {
            result.ok -> "$pastTense $subject$destination"
            done == 0 -> forFailure(result.failures.first(), verb)
            result.dropped -> "$pastTense $done of $total — the share dropped. The rest are where they were."
            else -> "$pastTense $done of $total. " + forFailure(result.failures.first(), verb)
        }
    }
}
