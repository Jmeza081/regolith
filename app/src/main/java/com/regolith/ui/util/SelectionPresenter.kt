package com.regolith.ui.util

import com.regolith.data.db.FolderEntity
import com.regolith.data.repository.LibraryRepository
import com.regolith.data.transfer.SelectionStore
import com.regolith.data.transfer.TransferRepository
import com.regolith.domain.transfer.FilePick
import com.regolith.domain.transfer.FolderPick
import com.regolith.domain.transfer.Selection
import com.regolith.domain.transfer.StorageCheck
import com.regolith.domain.transfer.coveredByAncestor
import com.regolith.domain.transfer.pathCoveredBy
import com.regolith.domain.transfer.tally
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import javax.inject.Inject

/**
 * A selection as one screen needs to see it.
 *
 * Null on a screen's UiState means "not selecting", and the contextual top
 * bar and the [com.regolith.ui.components.SelectionBar] are not drawn.
 */
data class SelectionUiState(
    /** Folders picked in their own right: checked, and tapping unpicks. */
    val pickedFolders: Set<Long> = emptySet(),
    /** Folders inside a picked folder: checked, and inert. */
    val coveredFolders: Set<Long> = emptySet(),
    val pickedFiles: Set<Long> = emptySet(),
    /**
     * Picked folder paths per share, so a row can test its own coverage
     * without the presenter having to know which rows are on screen.
     */
    val pickedPaths: Map<Long, Set<String>> = emptyMap(),
    /** The folder being looked at is itself inside a pick, so everything here is along for the ride. */
    val insideCovered: Boolean = false,
    /** What the contextual bar counts: picks, not the files they expand to. */
    val itemCount: Int = 0,
    val fileCount: Int = 0,
    val byteCount: Long = 0,
    /** A picked folder has yet to be walked, so [fileCount] is a floor rather than a total. */
    val estimated: Boolean = false,
    val alreadyKept: Int = 0,
    val unlistedFolders: Int = 0,
    /** False when the batch cannot fit; the bar says so and Download goes dead. */
    val hasRoom: Boolean = true,
    /** Bytes that would have to be freed first, when it cannot fit. */
    val shortfall: Long = 0,
) {
    /** "42 videos · 4.3 GB", or "Counting…" while a folder is still unknown. */
    val summary: String
        get() = when {
            itemCount == 0 -> "Nothing picked"
            estimated && fileCount == 0 -> "Counting…"
            else -> {
                val videos = if (fileCount == 1) "1 video" else "$fileCount videos"
                val prefix = if (estimated) "At least " else ""
                "$prefix$videos · ${formatBytes(byteCount)}"
            }
        }

    /** Whatever qualifies the summary, or null when nothing does. */
    val detail: String?
        get() {
            if (itemCount == 0) return "Hold or tap a video to start"
            val parts = buildList {
                if (!hasRoom) add("Not enough room · free ${formatBytes(shortfall)} more")
                if (unlistedFolders > 0) {
                    add(if (unlistedFolders == 1) "1 folder not listed yet" else "$unlistedFolders folders not listed yet")
                }
                if (alreadyKept > 0) add("$alreadyKept already here")
            }
            return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
        }

    /** Download only goes red when tapping it would actually do something. */
    val canDownload: Boolean get() = itemCount > 0 && hasRoom

    /**
     * Is a file in [folderRelPath] already coming, because one of its
     * ancestors is picked? Such a row is drawn checked and inert: picking
     * it again would be a no-op, and offering the tap says otherwise.
     */
    fun coversFile(shareId: Long, folderRelPath: String): Boolean {
        val paths = pickedPaths[shareId] ?: return false
        return pathCoveredBy(paths, folderRelPath)
    }
}

/**
 * The selection logic Browse, Library and Search all need, written once.
 *
 * Each screen's ViewModel collects [observe] into its own UiState and calls
 * [download] from its own Download handler; nothing about the selection is
 * duplicated per screen, which is what stops the three drifting apart.
 *
 * Not a ViewModel and not a singleton: a plain injectable collaborator, so
 * every screen gets its own instance around the one shared
 * [SelectionStore].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SelectionPresenter @Inject constructor(
    private val selection: SelectionStore,
    private val transfers: TransferRepository,
    private val library: LibraryRepository,
) {

    /** The live selection, totalled. Emits null whenever selection mode is off. */
    fun observe(): Flow<SelectionUiState?> = selection.state.flatMapLatest { sel ->
        if (sel == null) {
            flowOf(null)
        } else {
            val shareIds = sel.folders.map { it.shareId }.distinct()
            combine(
                library.observeFoldersInShares(shareIds),
                transfers.observeDoneFileIds(),
                transfers.observePendingPicks(),
            ) { folders, doneIds, pendingPicks ->
                present(sel, folders, doneIds, pendingPicks)
            }
        }
    }

    /**
     * Total a selection and work out which rows are merely covered.
     *
     * The tally sums each picked folder's OWN aggregates, which is exact
     * and double-counts nothing: a pick under another pick was dropped when
     * it was made, and `FolderEntity.fileCount` never includes a
     * subfolder's files. What it cannot do is see inside a folder nobody
     * has listed — hence `estimated`.
     */
    private fun present(
        sel: Selection,
        folders: List<FolderEntity>,
        doneFileIds: Set<Long>,
        pendingPicks: Int,
    ): SelectionUiState {
        val pickedIds = sel.folders.map { it.folderId }.toSet()
        // Every folder beneath a pick, per share, so a covered row can be drawn inert.
        val covered = mutableSetOf<Long>()
        var fileCount = 0
        var byteCount = 0L
        var unlisted = 0
        for (share in sel.folders.map { it.shareId }.distinct()) {
            val paths = sel.folders.filter { it.shareId == share }.map { it.relPath }.toSet()
            for (folder in folders) {
                if (folder.shareId != share) continue
                if (!pathCoveredBy(paths, folder.relPath)) continue
                if (folder.id !in pickedIds) covered += folder.id
                // Sum EVERY folder in the covered subtree, picked or not: a pick's own
                // fileCount is its direct files only, so the children carry the rest.
                fileCount += folder.fileCount
                byteCount += folder.byteCount
                if (folder.lastListedAtMs == null) unlisted++
            }
        }
        val fileTally = sel.tally(doneFileIds)
        fileCount += sel.files.size
        byteCount += sel.files.sumOf { it.sizeBytes }

        val storage = transfers.storage()
        val hasRoom = StorageCheck.hasRoom(storage.freeBytes, byteCount, 0)
        return SelectionUiState(
            pickedFolders = pickedIds,
            coveredFolders = covered,
            pickedFiles = sel.files.map { it.fileId }.toSet(),
            pickedPaths = sel.folders.groupBy { it.shareId }.mapValues { (_, v) -> v.map { it.relPath }.toSet() },
            itemCount = sel.itemCount,
            fileCount = fileCount,
            byteCount = byteCount,
            // A folder still awaiting its walk, or one nobody ever listed, both
            // mean the same thing to the reader: this number can only go up.
            estimated = unlisted > 0 || pendingPicks > 0,
            alreadyKept = fileTally.alreadyKept,
            unlistedFolders = unlisted,
            hasRoom = hasRoom,
            shortfall = if (hasRoom) 0 else StorageCheck.shortfall(storage.freeBytes, byteCount, 0),
        )
    }

    /** Is this row inside one of the picks, rather than a pick itself? */
    fun isCovered(shareId: Long, relPath: String): Boolean =
        selection.snapshot()?.coveredByAncestor(shareId, relPath) == true

    fun begin() = selection.begin()
    fun cancel() = selection.clear()
    fun toggleFolder(pick: FolderPick) = selection.toggleFolder(pick)
    fun toggleFile(pick: FilePick) = selection.toggleFile(pick)
    fun addAll(folders: List<FolderPick>, files: List<FilePick>) = selection.addAll(folders, files)

    /**
     * Turn the picks into durable work and leave selection mode.
     *
     * Two kinds of promise, two destinations. Files are known now, so they
     * become `transfers` rows immediately and copying can start. Folders
     * are not — a folder nobody opened has no file rows — so each becomes a
     * `download_picks` job for the queue worker to walk over SMB. Both end
     * in one `enqueue`, so the notification appears once.
     *
     * Returns how many files were queued outright; the folders' contribution
     * is not known until the walk.
     */
    suspend fun download(): Int {
        val sel = selection.snapshot() ?: return 0
        val queued = transfers.startAll(sel.files.map { it.fileId })
        transfers.addFolderPicks(sel.folders)
        selection.clear()
        return queued
    }
}
