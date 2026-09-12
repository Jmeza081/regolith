package com.regolith.domain.transfer

/**
 * A multi-selection of things to download, and the rules that govern it.
 *
 * Pure Kotlin: no Android, no Room, no coroutines, so every rule below is
 * unit-tested without an emulator. The mutable holder that keeps one of
 * these alive while the user picks is `data/transfer/SelectionStore`.
 *
 * The shape deliberately echoes the "Choose folders" picker (`share_roots`,
 * [com.regolith.domain.model.rootsCover]): a folder pick swallows everything
 * beneath it, a narrower pick under a broader one is dropped rather than
 * kept, and a row covered by an ancestor is shown checked but is inert.
 * Two features answering the same question the same way is worth more than
 * either of them being clever.
 */

/**
 * One folder picked for download — everything beneath it comes too.
 *
 * [fileCount] and [byteCount] are the folder's own aggregates as Room knows
 * them, carried here so the selection bar can total a batch without a query
 * per toggle. They are an ESTIMATE: zero for a folder the app has never
 * listed, and the walk at download time is what makes it exact.
 */
data class FolderPick(
    val folderId: Long,
    val shareId: Long,
    /** Share-relative, `/`-joined, no leading slash. Never empty — the share itself is not a pick. */
    val relPath: String,
    val fileCount: Int = 0,
    val byteCount: Long = 0,
    /** False when the folder has never been listed off the share, so its aggregates mean nothing. */
    val listed: Boolean = true,
)

/** One file picked directly. Carries its size so the tally needs no lookup. */
data class FilePick(
    val fileId: Long,
    val shareId: Long,
    /** The relPath of the folder holding it, so ancestor coverage is a string test. */
    val folderRelPath: String,
    val sizeBytes: Long = 0,
)

/** What has been picked. An instance of this is one selection session. */
data class Selection(
    val folders: Set<FolderPick> = emptySet(),
    val files: Set<FilePick> = emptySet(),
) {
    val isEmpty: Boolean get() = folders.isEmpty() && files.isEmpty()

    /** What the contextual bar counts: picks, not the files they expand to. */
    val itemCount: Int get() = folders.size + files.size

    /** Picked folder paths per share, for the covering tests below. */
    internal fun folderPathsIn(shareId: Long): Set<String> =
        folders.asSequence().filter { it.shareId == shareId }.map { it.relPath }.toSet()
}

/**
 * True when [relPath] is one of [paths] or sits inside one.
 *
 * The separator in `"$it/"` is what makes this a path test rather than a
 * string test: `Films Archive` is NOT inside `Films`, however much the
 * prefix suggests otherwise.
 *
 * Unlike [com.regolith.domain.model.rootsCover], an empty [paths] covers
 * NOTHING. "No roots means the whole share" is a statement about a library's
 * shape; "nothing picked" is a statement about a selection, and it means
 * nothing is picked.
 */
fun pathCoveredBy(paths: Set<String>, relPath: String): Boolean =
    paths.any { relPath == it || relPath.startsWith("$it/") }

/**
 * Add or remove a folder pick.
 *
 * Picking a folder drops any pick strictly beneath it and any file pick
 * inside it, exactly as `SourceRepository.addShareRoot` deletes narrower
 * roots: the broader answer replaces the narrower one, so a batch can never
 * count the same file twice. A folder already covered by an ancestor is not
 * pickable at all and returns the selection unchanged.
 */
fun Selection.toggleFolder(pick: FolderPick): Selection {
    val mine = folders.firstOrNull { it.folderId == pick.folderId }
    if (mine != null) return copy(folders = folders - mine)
    // Covered by an ancestor: the row is inert, so this is a no-op rather than a second pick.
    if (coveredByAncestor(pick.shareId, pick.relPath)) return this
    val prefix = "${pick.relPath}/"
    return copy(
        folders = folders.filterNot { it.shareId == pick.shareId && it.relPath.startsWith(prefix) }.toSet() + pick,
        files = files.filterNot { it.shareId == pick.shareId && (it.folderRelPath == pick.relPath || it.folderRelPath.startsWith(prefix)) }.toSet(),
    )
}

/**
 * Add or remove a file pick.
 *
 * A file inside a picked folder is already coming, so picking it is a no-op
 * — the row is drawn checked and inert for exactly that reason.
 */
fun Selection.toggleFile(pick: FilePick): Selection {
    val mine = files.firstOrNull { it.fileId == pick.fileId }
    if (mine != null) return copy(files = files - mine)
    if (coveredByAncestor(pick.shareId, pick.folderRelPath)) return this
    return copy(files = files + pick)
}

/**
 * Is [relPath] inside a picked folder, without being that folder itself?
 *
 * This is the picker's `includedBy` in a different costume: the answer is
 * what decides whether a row is toggleable or merely along for the ride.
 */
fun Selection.coveredByAncestor(shareId: Long, relPath: String): Boolean {
    val paths = folderPathsIn(shareId)
    return paths.any { relPath != it && relPath.startsWith("$it/") }
}

/** True when this exact folder is picked in its own right. */
fun Selection.hasFolder(folderId: Long): Boolean = folders.any { it.folderId == folderId }

/** True when this exact file is picked in its own right. */
fun Selection.hasFile(fileId: Long): Boolean = files.any { it.fileId == fileId }

/**
 * What the selection bar says.
 *
 * [estimated] is the honest part: a picked folder the app has never listed
 * contributes nothing to [fileCount], so the bar must say "counting…"
 * instead of printing a number that is wrong.
 */
data class SelectionTally(
    val fileCount: Int = 0,
    val byteCount: Long = 0,
    /** Picked files that are already on the device, so the batch will skip them. */
    val alreadyKept: Int = 0,
    /** Picked folders whose contents are unknown until the download walks them. */
    val unlistedFolders: Int = 0,
) {
    val estimated: Boolean get() = unlistedFolders > 0
}

/**
 * Total a selection.
 *
 * Folder aggregates are DIRECT per-folder counts, so summing the picks adds
 * nothing twice — a pick under another pick was dropped at toggle time, and
 * [FolderPick.fileCount] never includes a subfolder's files. [doneFileIds]
 * is the set of files already downloaded, which the bar reports separately
 * rather than subtracting: "34 videos · 3 already here" tells you more than
 * "31 videos".
 */
fun Selection.tally(doneFileIds: Set<Long> = emptySet()): SelectionTally {
    var fileCount = 0
    var bytes = 0L
    var unlisted = 0
    for (folder in folders) {
        if (!folder.listed) unlisted++
        fileCount += folder.fileCount
        bytes += folder.byteCount
    }
    for (file in files) {
        fileCount++
        bytes += file.sizeBytes
    }
    return SelectionTally(
        fileCount = fileCount,
        byteCount = bytes,
        alreadyKept = files.count { it.fileId in doneFileIds },
        unlistedFolders = unlisted,
    )
}
