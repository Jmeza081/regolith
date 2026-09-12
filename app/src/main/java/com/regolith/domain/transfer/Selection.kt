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

/**
 * What has been picked. An instance of this is one selection session.
 *
 * A folder pick is additive — everything beneath it comes — and the two
 * exclusion sets are how the user takes some of that back: "this folder,
 * minus these". Exclusions only ever sit INSIDE a pick; unpicking the
 * folder drops them, and picking it afresh drops them too, because a new
 * pick means "all of it".
 */
data class Selection(
    val folders: Set<FolderPick> = emptySet(),
    val files: Set<FilePick> = emptySet(),
    /** Files a picked folder would bring that the user took back out. */
    val excludedFiles: Set<FilePick> = emptySet(),
    /** Subfolders a picked folder would bring that the user took back out, subtree and all. */
    val excludedFolders: Set<FolderPick> = emptySet(),
) {
    val isEmpty: Boolean get() = folders.isEmpty() && files.isEmpty()

    /** What the contextual bar counts: picks, not the files they expand to. Exclusions are not picks. */
    val itemCount: Int get() = folders.size + files.size

    /** Picked folder paths per share, for the covering tests below. */
    internal fun folderPathsIn(shareId: Long): Set<String> =
        folders.asSequence().filter { it.shareId == shareId }.map { it.relPath }.toSet()

    /** Excluded subfolder paths per share. */
    internal fun excludedPathsIn(shareId: Long): Set<String> =
        excludedFolders.asSequence().filter { it.shareId == shareId }.map { it.relPath }.toSet()
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
    // Unpicking a folder takes its exclusions with it: they were only ever
    // "minus these" against a pick that no longer exists.
    if (mine != null) return copy(folders = folders - mine).dropExclusionsUnder(pick.shareId, pick.relPath)
    val excluded = excludedFolders.firstOrNull { it.folderId == pick.folderId }
    if (excluded != null) return copy(excludedFolders = excludedFolders - excluded)
    // Inside a pick: tapping takes THIS subtree back out, rather than
    // picking it twice or doing nothing. "This folder, minus that one."
    if (coversFolder(pick.shareId, pick.relPath)) return copy(excludedFolders = excludedFolders + pick)
    val prefix = "${pick.relPath}/"
    return copy(
        folders = folders.filterNot { it.shareId == pick.shareId && it.relPath.startsWith(prefix) }.toSet() + pick,
        files = files.filterNot { it.shareId == pick.shareId && (it.folderRelPath == pick.relPath || it.folderRelPath.startsWith(prefix)) }.toSet(),
    ).dropExclusionsUnder(pick.shareId, pick.relPath)
}

/**
 * Add or remove a file pick.
 *
 * A file inside a picked folder is already coming. Tapping it does NOT pick
 * it again and does NOT do nothing: it takes that one file back out, which
 * is what anyone tapping a checked row inside a picked folder means. Tap it
 * again and it is back in.
 */
fun Selection.toggleFile(pick: FilePick): Selection {
    val mine = files.firstOrNull { it.fileId == pick.fileId }
    if (mine != null) return copy(files = files - mine)
    val excluded = excludedFiles.firstOrNull { it.fileId == pick.fileId }
    if (excluded != null) return copy(excludedFiles = excludedFiles - excluded)
    if (coversFileIn(pick.shareId, pick.folderRelPath)) return copy(excludedFiles = excludedFiles + pick)
    return copy(files = files + pick)
}

/**
 * "Select all": bring a file in, whatever state it is in. Unlike
 * [toggleFile] this never takes anything OUT — select-all on a screen full
 * of covered rows must not turn into exclude-all.
 */
fun Selection.includeFile(pick: FilePick): Selection = when {
    hasFile(pick.fileId) -> this
    isExcludedFile(pick.fileId) -> copy(excludedFiles = excludedFiles.filterNot { it.fileId == pick.fileId }.toSet())
    coversFileIn(pick.shareId, pick.folderRelPath) -> this
    else -> copy(files = files + pick)
}

/** "Select all" for a folder: in, whatever it was. Never excludes. */
fun Selection.includeFolder(pick: FolderPick): Selection = when {
    hasFolder(pick.folderId) -> this
    isExcludedFolder(pick.folderId) -> copy(excludedFolders = excludedFolders.filterNot { it.folderId == pick.folderId }.toSet())
    coversFolder(pick.shareId, pick.relPath) -> this
    else -> toggleFolder(pick)
}

/** Exclusions sitting at or under [relPath] go, because the pick they qualified has changed. */
private fun Selection.dropExclusionsUnder(shareId: Long, relPath: String): Selection {
    val prefix = "$relPath/"
    return copy(
        excludedFiles = excludedFiles.filterNot { it.shareId == shareId && (it.folderRelPath == relPath || it.folderRelPath.startsWith(prefix)) }.toSet(),
        excludedFolders = excludedFolders.filterNot { it.shareId == shareId && (it.relPath == relPath || it.relPath.startsWith(prefix)) }.toSet(),
    )
}

fun Selection.isExcludedFile(fileId: Long): Boolean = excludedFiles.any { it.fileId == fileId }
fun Selection.isExcludedFolder(folderId: Long): Boolean = excludedFolders.any { it.folderId == folderId }

/**
 * Is a folder at [relPath] coming because an ANCESTOR is picked — not
 * picked itself, and not inside an excluded subtree?
 */
fun Selection.coversFolder(shareId: Long, relPath: String): Boolean =
    coveredByAncestor(shareId, relPath) && !pathCoveredBy(excludedPathsIn(shareId), relPath)

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

/**
 * Is a file in [folderRelPath] already coming, because its own folder or one
 * of that folder's ancestors is picked?
 *
 * Inclusive where [coveredByAncestor] is exclusive, and the difference is
 * the point. A picked FOLDER is not "covered" by itself — it is picked, and
 * its row stays toggleable so it can be unpicked. A FILE directly inside a
 * picked folder has no such standing: the folder is bringing it either way,
 * so its row is checked and inert.
 */
fun Selection.coversFileIn(shareId: Long, folderRelPath: String): Boolean =
    pathCoveredBy(folderPathsIn(shareId), folderRelPath) && !pathCoveredBy(excludedPathsIn(shareId), folderRelPath)

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
    /** Files and subfolders taken back out of a picked folder. */
    val leftOut: Int = 0,
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
    // Only exclusions that are DIRECT children of a pick were counted above
    // (a pick carries its own direct counts, not its subfolders'), so only
    // those come back off here. The presenter, which sees every folder,
    // does the exact subtraction.
    val pickedPaths = folders.map { it.shareId to it.relPath }.toSet()
    for (file in excludedFiles) {
        if ((file.shareId to file.folderRelPath) in pickedPaths) {
            fileCount--
            bytes -= file.sizeBytes
        }
    }
    return SelectionTally(
        fileCount = fileCount.coerceAtLeast(0),
        byteCount = bytes.coerceAtLeast(0),
        alreadyKept = files.count { it.fileId in doneFileIds },
        unlistedFolders = unlisted,
        leftOut = excludedFiles.size + excludedFolders.size,
    )
}

/**
 * What one folder pick leaves out, resolved for the download job: the file
 * ids to skip and the subfolder paths the walk should not enter.
 */
data class FolderExclusions(val fileIds: Set<Long> = emptySet(), val paths: Set<String> = emptySet()) {
    val isEmpty: Boolean get() = fileIds.isEmpty() && paths.isEmpty()
}

/** The exclusions sitting inside [pick] — its own, not another pick's. */
fun Selection.exclusionsUnder(pick: FolderPick): FolderExclusions {
    val prefix = "${pick.relPath}/"
    fun inside(shareId: Long, path: String) = shareId == pick.shareId && (path == pick.relPath || path.startsWith(prefix))
    return FolderExclusions(
        fileIds = excludedFiles.filter { inside(it.shareId, it.folderRelPath) }.map { it.fileId }.toSet(),
        paths = excludedFolders.filter { inside(it.shareId, it.relPath) }.map { it.relPath }.toSet(),
    )
}
