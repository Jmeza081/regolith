package com.regolith.domain.fileops

/**
 * What can go wrong when the app changes a file ON THE SHARE, in the terms
 * the UI has words for. One per item, because a batch is N independent
 * operations: the share dropping halfway leaves the rest untouched rather
 * than failing the lot.
 */
enum class FileOpError {
    /** Something already has that name in the destination. Nothing was touched. */
    NAME_TAKEN,

    /** The share is read-only to us, or the server refused. */
    FORBIDDEN,

    /** The share went away. Whatever was done stays done. */
    UNREACHABLE,

    /** It was not there to begin with — most likely deleted by someone else. */
    NOT_FOUND,

    /** The name has characters a share will not take, or is empty. */
    BAD_NAME,

    /** A folder was aimed at itself, at its own subtree, or at another share. */
    BAD_DESTINATION,

    OTHER,
}

/**
 * One thing an operation is about: a file, or a folder and everything under it.
 *
 * Why not a bare id. Folders and files are different tables and both number
 * from 1, so in a batch holding some of each a `Long` says nothing about
 * what it points at. The flag travels WITH the id for the same reason a
 * foreign key names its table.
 */
data class FileOpTarget(val id: Long, val isFolder: Boolean) {
    companion object {
        fun file(id: Long) = FileOpTarget(id, isFolder = false)
        fun folder(id: Long) = FileOpTarget(id, isFolder = true)
        fun files(ids: Collection<Long>): List<FileOpTarget> = ids.map(::file)
    }
}

/** One item that did not make it, with enough to name it in a message. */
data class FileOpFailure(val target: FileOpTarget, val name: String, val error: FileOpError)

/**
 * The outcome of one rename, move or delete batch.
 *
 * [done] and [failures] together always account for every id asked for:
 * the SMB probe showed each file is either changed or not, never half,
 * so there is no third state to represent.
 */
data class FileOpResult(
    val done: List<FileOpTarget> = emptyList(),
    val failures: List<FileOpFailure> = emptyList(),
) {
    val ok: Boolean get() = failures.isEmpty()
    val partial: Boolean get() = done.isNotEmpty() && failures.isNotEmpty()

    /** The share dropped mid-batch, so the remainder was never attempted. */
    val dropped: Boolean get() = failures.any { it.error == FileOpError.UNREACHABLE }

    /** The ids of the files that made it, for callers that only ever pass files. */
    val doneFileIds: List<Long> get() = done.filterNot { it.isFolder }.map { it.id }

    companion object {
        fun failed(target: FileOpTarget, name: String, error: FileOpError) =
            FileOpResult(failures = listOf(FileOpFailure(target, name, error)))
    }
}

/**
 * Names a share will accept. Checked before anything is sent, so a bad
 * name is a message in the dialog rather than a server error after the
 * fact.
 *
 * The reserved set is Windows', not POSIX's: a Samba share on Linux would
 * take `a:b`, but the same file on a Windows client would be unreachable,
 * and these files are meant to be read from anywhere.
 */
object FileNames {
    private val reserved = charArrayOf('\\', '/', ':', '*', '?', '"', '<', '>', '|')

    /** The longest base name we will write; SMB allows 255 for the whole name. */
    const val MAX_BASE = 200

    /**
     * The trimmed base name, or null when it cannot be used.
     *
     * Surrounding whitespace is trimmed, but a trailing DOT is refused
     * rather than trimmed away: Windows drops it silently, so "Heat." would
     * land on the share as "Heat" and the rename would look like it had
     * done nothing.
     */
    fun cleanBase(input: String): String? {
        val trimmed = input.trim()
        if (trimmed.isEmpty() || trimmed.length > MAX_BASE) return null
        if (trimmed.any { it in reserved || it.code < 0x20 }) return null
        if (trimmed.endsWith('.')) return null
        if (trimmed == "." || trimmed == "..") return null
        return trimmed
    }

    /**
     * A folder's whole name goes through [cleanBase] unchanged: a folder has
     * no extension to protect, so what the user types is what it is called.
     */
    fun cleanFolderName(input: String): String? = cleanBase(input)

    /** "Heat.1995" + "mkv" -> "Heat.1995.mkv"; a file with no extension keeps none. */
    fun withExtension(base: String, ext: String): String = if (ext.isEmpty()) base else "$base.$ext"

    /** The part the user edits: everything before the last dot. */
    fun baseOf(fileName: String): String = fileName.substringBeforeLast('.', fileName)
}
