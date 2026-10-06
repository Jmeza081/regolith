package com.regolith.ui.util

import com.regolith.domain.fileops.FileOpError
import com.regolith.domain.fileops.FileOpFailure
import com.regolith.domain.fileops.FileOpResult
import com.regolith.domain.fileops.FileOpTarget

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
        // Three refusals with one sentence, because they share a shape:
        // the destination is not somewhere this item can be.
        FileOpError.BAD_DESTINATION -> "${failure.name} can't go there. A folder can't move inside itself, " +
            "and nothing moves between shares."
        FileOpError.COMPANION_TAKEN -> "Couldn't $verb ${failure.name}: ${failure.detail ?: "a file"} is already there, " +
            "and a file that goes with it needs that name."
        FileOpError.OTHER -> "Couldn't $verb ${failure.name}."
    }

    /**
     * Why a move or delete will not start: something was taken back out of
     * a picked folder, and moving or deleting the folder would take it too.
     * [verb] is the infinitive ("move", "delete"); [folderNames] are the
     * folders concerned, named when there is just one.
     */
    fun forLeftOut(verb: String, folderNames: List<String>): String {
        val name = folderNames.singleOrNull()
        val capital = verb.replaceFirstChar { it.uppercase() }
        return if (name != null) {
            "You un-picked something inside “$name”, so $capital would take it too. Open “$name” and pick what to $verb."
        } else {
            "You un-picked something inside folders you picked, so $capital would take it too. Open them and pick what to $verb."
        }
    }

    /**
     * The delete dialog's body when only videos are going: one by its
     * [names], or several by their size. [companions] is how many files go
     * with them (`Companions`: their subtitles, chapters, own pictures), said
     * because nothing in the list shows them going. Shared by Browse and a
     * video's own page, so one delete is never described two ways.
     */
    fun forDeletingVideos(names: List<String>, sizeLabel: String, companions: Int): String {
        val forever = "This can't be undone"
        val alsoGone = "the chapters you wrote and where you left off go with"
        val name = names.singleOrNull()
        return if (name != null) {
            val subject = when (companions) {
                0 -> "$name leaves"
                1 -> "$name and the file that shares its name leave"
                else -> "$name and the $companions files that share its name leave"
            }
            "$subject the share for good — $sizeLabel. $forever, and $alsoGone it."
        } else {
            val along = when (companions) {
                0 -> ""
                1 -> ", with the file that shares a video's name"
                else -> ", with the $companions files that share the videos' names"
            }
            "$sizeLabel leaves the share for good$along. $forever, and $alsoGone them."
        }
    }

    /**
     * The delete dialog's three lines, kept together because they have to
     * agree, for every screen that picks things ([FileActions]).
     *
     * A folder is the case worth being careful with: the server's delete is
     * recursive and takes everything, not only the videos the counts are
     * made of (subtitles, artwork, files in folders never opened here), so
     * the body says what Regolith can count AND admits to what it cannot.
     * The counts come from rows already on the device, which is why the
     * dialog opens instantly instead of behind a network walk.
     */
    fun deleteTitle(target: DeleteTarget): String {
        val n = target.targets.size
        return when (kindOf(target)) {
            Picked.VIDEOS -> if (n == 1) "Delete this video?" else "Delete $n videos?"
            Picked.FILES -> if (n == 1) "Delete this file?" else "Delete $n files?"
            Picked.FOLDER -> "Delete this folder?"
            Picked.MIXED -> "Delete $n items?"
        }
    }

    fun deleteConfirmLabel(target: DeleteTarget): String {
        val n = target.targets.size
        return when (kindOf(target)) {
            Picked.VIDEOS -> if (n == 1) "Delete video" else "Delete $n videos"
            Picked.FILES -> if (n == 1) "Delete file" else "Delete $n files"
            Picked.FOLDER -> "Delete folder"
            Picked.MIXED -> "Delete $n items"
        }
    }

    /** What a delete is of, for its three lines. */
    private enum class Picked { VIDEOS, FILES, FOLDER, MIXED }

    private fun kindOf(target: DeleteTarget): Picked {
        val videos = target.targets.size - target.folderCount - target.otherCount
        return when {
            target.folderCount == 0 && target.otherCount == 0 -> Picked.VIDEOS
            target.folderCount == 0 && videos == 0 -> Picked.FILES
            target.folderCount == 1 && target.targets.size == 1 -> Picked.FOLDER
            else -> Picked.MIXED
        }
    }

    fun deleteBody(target: DeleteTarget): String {
        val forever = "This can't be undone"
        val insideFolders = "A folder takes everything inside it, not just its videos"
        val alsoGone = "the chapters you wrote and where you left off go with"
        return when (kindOf(target)) {
            // Videos only: the same words as a video's own page.
            Picked.VIDEOS -> forDeletingVideos(target.names, target.sizeLabel, target.companionCount)
            // A poster, subtitles: nothing hangs off them in the app.
            Picked.FILES -> {
                val name = target.names.singleOrNull()
                if (name != null) "$name leaves the share for good — ${target.sizeLabel}. $forever."
                else "${target.sizeLabel} leaves the share for good. $forever."
            }
            // One folder, named, with what is known to be inside it.
            Picked.FOLDER -> {
                val holds = if (target.videoCount == 0) "no videos in it" else "${videos(target.videoCount)} · ${target.sizeLabel}"
                "${target.names.first()} and everything inside it leaves the share for good — $holds. " +
                    "$insideFolders. $forever, and $alsoGone them."
            }
            Picked.MIXED -> {
                val videoPicks = target.targets.size - target.folderCount - target.otherCount
                val picked = listOfNotNull(
                    target.folderCount.takeIf { it > 0 }?.let { if (it == 1) "1 folder" else "$it folders" },
                    videoPicks.takeIf { it > 0 }?.let { videos(it) },
                    target.otherCount.takeIf { it > 0 }?.let { if (it == 1) "1 other file" else "$it other files" },
                ).let { parts -> if (parts.size == 1) parts.first() else parts.dropLast(1).joinToString(", ") + " and " + parts.last() }
                val along = when (target.companionCount) {
                    0 -> ""
                    1 -> " So does the file that shares a picked video's name."
                    else -> " So do the ${target.companionCount} files that share the picked videos' names."
                }
                val inside = if (target.folderCount > 0) " $insideFolders." else ""
                "$picked leave the share for good — ${videos(target.videoCount)} · ${target.sizeLabel} in all.$along$inside $forever."
            }
        }
    }

    /** "1 video" / "9 videos": the delete dialog counts videos, not files on disk. */
    private fun videos(n: Int): String = if (n == 1) "1 video" else "$n videos"

    /**
     * The line under the rename field. A folder moves as one; a video keeps
     * its extension ([ext], outside the field where it cannot be typed away),
     * and its [companions] are renamed to match it; another file keeps its
     * extension and nothing else follows it.
     */
    fun forRenameNote(kind: FileOpTarget.Kind, ext: String, companions: Int): String {
        if (kind == FileOpTarget.Kind.FOLDER) return "Everything inside keeps its place — the folder moves as one."
        if (kind == FileOpTarget.Kind.OTHER) return if (ext.isEmpty()) "Only this file is renamed." else "Keeps .$ext. Only this file is renamed."
        val follows = if (ext.isEmpty()) "Chapters and your place follow the new name" else "Keeps .$ext — chapters and your place follow the new name"
        return when (companions) {
            0 -> "$follows."
            1 -> "$follows, and the file that shares its name is renamed to match."
            else -> "$follows, and the $companions files that share its name are renamed to match."
        }
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
        val subject = subjectFor(result.done)
        val destination = where?.let { " to $it" } ?: ""
        return when {
            result.ok -> "$pastTense $subject$destination"
            done == 0 -> forFailure(result.failures.first(), verb)
            result.dropped -> "$pastTense $done of $total — the share dropped. The rest are where they were."
            else -> "$pastTense $done of $total. " + forFailure(result.failures.first(), verb)
        }
    }

    /**
     * "4 videos", "1 folder", "5 items" — what a batch is, in the words the
     * user picked it with.
     *
     * A folder is never counted as its contents. "Moved 1 folder" is what
     * happened; "Moved 112 videos" would be a different sentence about a
     * different gesture, and the folder is the thing that moved.
     */
    fun subjectFor(targets: Collection<FileOpTarget>): String {
        val folders = targets.count { it.isFolder }
        val videos = targets.count { it.isVideo }
        val others = targets.count { it.isOther }
        return when (targets.size) {
            videos -> if (videos == 1) "1 video" else "$videos videos"
            folders -> if (folders == 1) "1 folder" else "$folders folders"
            others -> if (others == 1) "1 file" else "$others files"
            else -> "${targets.size} items"
        }
    }
}
