package com.regolith.ui.util

import com.regolith.data.db.FolderEntity
import com.regolith.data.db.ShareFileEntity
import com.regolith.data.fileops.FileOpsRepository
import com.regolith.data.repository.FolderLookup
import com.regolith.data.transfer.SelectionStore
import com.regolith.domain.artwork.FolderPoster
import com.regolith.domain.fileops.FileOpResult
import com.regolith.domain.fileops.FileOpTarget
import com.regolith.domain.fileops.ReadOnlySource
import com.regolith.domain.media.MediaFileTypes
import com.regolith.domain.transfer.Selection
import com.regolith.domain.transfer.foldersWithExclusions
import com.regolith.ui.components.MoveChild
import com.regolith.ui.components.MoveSheetState
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Rename, move and delete for whatever is picked, for every screen that
 * picks things: Browse and the Library. It holds the dialogs' state, the
 * move sheet and the messages, and works out up front which verbs can work.
 *
 * Web analogy: a `useFileActions()` hook. Each ViewModel makes one with its
 * own `viewModelScope` ([Factory]), and `FileActionsHost` draws whatever
 * [state] says, so a delete reads and behaves the same on every screen.
 * The picks themselves live in the app-wide [SelectionStore], because a
 * selection outlives the screen it was started on; this reads them there —
 * except for one thing acted on by itself, the lightbox's picture
 * ([renameOne], [moveOne], [deleteOne]), which leaves the selection alone.
 *
 * Pictures are spoken of as pictures, and the two whose going changes
 * another screen say so before anything happens: a collection's poster
 * (its tile falls back to another picture, or a mosaic) and a video's own
 * picture (renamed, it is no longer that video's).
 */
class FileActions @AssistedInject constructor(
    @Assisted private val scope: CoroutineScope,
    private val lookup: FolderLookup,
    private val fileOps: FileOpsRepository,
    private val selection: SelectionStore,
) {
    @AssistedFactory
    interface Factory {
        fun create(scope: CoroutineScope): FileActions
    }

    private val _state = MutableStateFlow(FileActionsState())
    val state: StateFlow<FileActionsState> = _state.asStateFlow()

    /**
     * Where each picked item was when the move sheet opened: a folder's
     * parent, a file's folder. "They're already here" and Undo both ask it,
     * so a pick gathered from several folders goes back to each of them.
     */
    private var origins: Map<FileOpTarget, Long> = emptyMap()

    /**
     * Picks handed over directly — the lightbox's one picture — instead of
     * the selection's. Set as such a verb starts and forgotten when it ends
     * or is called off; while set, the selection is neither read nor cleared.
     */
    private var explicit: List<FileOpTarget>? = null

    /** The picks that are pictures, found as a verb starts: a target says only that it is not a video. */
    private var pictures: Set<FileOpTarget> = emptySet()

    init {
        scope.launch {
            selection.state.collectLatest { sel -> _state.update { it.copy(verbs = verbsFor(sel)) } }
        }
    }

    private suspend fun verbsFor(sel: Selection?): FileVerbs {
        if (sel == null) return FileVerbs()
        val shares = (sel.folders.map { it.shareId } + sel.files.map { it.shareId } + sel.others.map { it.shareId }).toSet()
        return FileVerbs.of(itemCount = sel.itemCount, shares = shares.size, readOnly = fileOps.readOnly(shares))
    }

    /** What is picked, as the repository wants it: ids that say what they point at. */
    private fun picked(): List<FileOpTarget>? {
        explicit?.let { return it }
        val sel = selection.snapshot() ?: return null
        val targets = sel.folders.map { FileOpTarget.folder(it.folderId) } +
            sel.files.map { FileOpTarget.file(it.fileId) } +
            sel.others.map { FileOpTarget.other(it.otherId) }
        return targets.takeIf { it.isNotEmpty() }
    }

    // ── rename ─────────────────────────────────────────────────────────

    fun startRename() {
        if (!_state.value.verbs.canRename) return
        explicit = null
        beginRename(picked()?.singleOrNull() ?: return)
    }

    /** Rename [target] by itself, whatever is selected: the lightbox's More › Rename. */
    fun renameOne(target: FileOpTarget) {
        explicit = listOf(target)
        beginRename(target)
    }

    private fun beginRename(target: FileOpTarget) {
        scope.launch {
            val name = nameOf(target) ?: return@launch
            val companions = if (target.isVideo) lookup.companionCount(listOf(target.id)) else 0
            val other = if (target.isOther) lookup.other(target.id) else null
            val picture = other != null && MediaFileTypes.isPicture(other.name)
            pictures = if (picture) setOf(target) else emptySet()
            val warning = other?.let { posterGoing(it, going = setOf(it.id)) ?: videoPictureOf(it) }
            _state.update { it.copy(renaming = RenameTarget(target, name, companions, picture, warning)) }
        }
    }

    fun rename(newName: String) {
        val target = _state.value.renaming ?: return
        _state.update { it.copy(renaming = null) }
        scope.launch {
            val result = fileOps.rename(target.target, newName)
            finishVerb()
            report(result, verb = "rename", past = "Renamed")
        }
    }

    fun cancelRename() {
        explicit = null
        _state.update { it.copy(renaming = null) }
    }

    // ── delete ─────────────────────────────────────────────────────────

    /**
     * A folder delete reaches through the subtree, so the dialog counts
     * through it too: the videos and bytes below a picked folder are what is
     * actually going, however few things were tapped.
     */
    fun startDelete() {
        if (!_state.value.verbs.canDelete) return
        explicit = null
        beginDelete(picked() ?: return)
    }

    /** Delete [target] by itself, whatever is selected: the lightbox's More › Delete from share. */
    fun deleteOne(target: FileOpTarget) {
        explicit = listOf(target)
        beginDelete(listOf(target))
    }

    private fun beginDelete(targets: List<FileOpTarget>) {
        scope.launch {
            if (refusedForLeftOut("delete")) return@launch
            val names = mutableListOf<String>()
            var videos = 0
            var bytes = 0L
            var folders = 0
            var others = 0
            val pictured = mutableSetOf<FileOpTarget>()
            val otherRows = mutableListOf<ShareFileEntity>()
            for (target in targets) {
                when (target.kind) {
                    FileOpTarget.Kind.FOLDER -> {
                        val folder = lookup.folder(target.id) ?: continue
                        folders++
                        names += folder.name
                        val inside = lookup.filesUnder(target.id)
                        videos += inside.size
                        bytes += inside.sumOf { it.sizeBytes }
                    }
                    FileOpTarget.Kind.FILE -> {
                        val file = lookup.file(target.id) ?: continue
                        names += file.name
                        videos++
                        bytes += file.sizeBytes
                    }
                    FileOpTarget.Kind.OTHER -> {
                        val other = lookup.other(target.id) ?: continue
                        names += other.name
                        others++
                        bytes += other.sizeBytes
                        otherRows += other
                        if (MediaFileTypes.isPicture(other.name)) pictured += target
                    }
                }
            }
            pictures = pictured
            val companions = lookup.companionCount(targets.filter { it.isVideo }.map { it.id })
            val going = otherRows.mapTo(HashSet()) { it.id }
            val posterNote = otherRows.firstNotNullOfOrNull { posterGoing(it, going) }
            _state.update {
                it.copy(
                    confirmingDelete = DeleteTarget(
                        targets = targets,
                        names = names,
                        sizeLabel = formatBytes(bytes),
                        videoCount = videos,
                        folderCount = folders,
                        companionCount = companions,
                        otherCount = others,
                        pictureCount = pictured.size,
                        posterNote = posterNote,
                    ),
                )
            }
        }
    }

    fun confirmDelete() {
        val target = _state.value.confirmingDelete ?: return
        _state.update { it.copy(confirmingDelete = null) }
        scope.launch {
            if (refusedForLeftOut("delete")) return@launch
            val result = fileOps.delete(target.targets)
            finishVerb()
            report(result, verb = "delete", past = "Deleted")
        }
    }

    fun cancelDelete() {
        explicit = null
        _state.update { it.copy(confirmingDelete = null) }
    }

    // ── move ───────────────────────────────────────────────────────────

    /**
     * Open the destination picker at [here], the folder on screen, when the
     * picks are on its share; otherwise at the top of theirs. The Library's
     * first wall spans shares and has no folder of its own, and a move
     * cannot leave the share it starts on.
     */
    fun startMove(here: Long?) {
        if (!_state.value.verbs.canMove) return
        explicit = null
        beginMove(here, picked() ?: return)
    }

    /** Move [target] by itself, whatever is selected: the lightbox's More › Move to…, opening at [here]. */
    fun moveOne(target: FileOpTarget, here: Long?) {
        explicit = listOf(target)
        beginMove(here, listOf(target))
    }

    private fun beginMove(here: Long?, targets: List<FileOpTarget>) {
        scope.launch {
            if (refusedForLeftOut("move")) return@launch
            pictures = picturesAmong(targets)
            origins = originsOf(targets)
            val share = shareOf(targets) ?: return@launch
            val start = here?.let { lookup.folder(it) }?.takeIf { it.shareId == share }?.id ?: lookup.rootFolder(share).id
            _state.update { it.copy(moveSheet = sheetFor(start, chosenId = start, targets = targets)) }
        }
    }

    /** Walk into a folder. Walking in also chooses it: that is what walking in means here. */
    fun moveWalk(folderId: Long) {
        scope.launch {
            val targets = picked() ?: return@launch
            _state.update { it.copy(moveSheet = sheetFor(folderId, chosenId = folderId, targets = targets)) }
        }
    }

    fun moveUp() {
        scope.launch {
            val sheet = _state.value.moveSheet ?: return@launch
            val targets = picked() ?: return@launch
            val parent = lookup.folder(sheet.currentFolderId)?.parentId ?: return@launch
            _state.update { it.copy(moveSheet = sheetFor(parent, chosenId = parent, targets = targets)) }
        }
    }

    /** Choose a folder without walking into it. */
    fun moveChoose(chosenId: Long) {
        scope.launch {
            val sheet = _state.value.moveSheet ?: return@launch
            val targets = picked() ?: return@launch
            _state.update { it.copy(moveSheet = sheetFor(sheet.currentFolderId, chosenId = chosenId, targets = targets)) }
        }
    }

    fun dismissMove() {
        explicit = null
        _state.update { it.copy(moveSheet = null) }
    }

    fun confirmMove() {
        val sheet = _state.value.moveSheet ?: return
        val targets = picked() ?: return
        val from = origins
        _state.update { it.copy(moveSheet = null) }
        scope.launch {
            if (refusedForLeftOut("move")) return@launch
            val result = fileOps.move(targets, sheet.chosenFolderId)
            finishVerb()
            report(
                result, verb = "move", past = "Moved", where = sheet.chosenName,
                // Only when everything made it: a half-done batch undone
                // halfway is a worse place to be than where it stopped.
                undo = if (result.ok && result.done.isNotEmpty()) UndoMove.of(result.done, from) else null,
            )
        }
    }

    /** Put them back, each where it came from. The inverse of a move is the same single rename. */
    fun undoMove() {
        val undo = _state.value.message?.undo ?: return
        _state.update { it.copy(message = null) }
        scope.launch {
            var all = FileOpResult()
            for ((folderId, targets) in undo.backTo) {
                val result = fileOps.move(targets, folderId)
                all = FileOpResult(all.done + result.done, all.failures + result.failures)
            }
            report(all, verb = "move", past = "Moved back")
        }
    }

    // ── a folder that is not there yet ─────────────────────────────────

    /** Ask for the name. The sheet stays up behind the prompt. */
    fun startNewFolder() {
        val sheet = _state.value.moveSheet ?: return
        _state.update { it.copy(newFolderIn = sheet.currentFolderId) }
    }

    fun cancelNewFolder() = _state.update { it.copy(newFolderIn = null) }

    /**
     * Create it and choose it, without closing the sheet: the folder was
     * asked for as a destination, so landing back on the picker with it
     * already ticked is the shortest path to the move that prompted it.
     */
    fun createFolder(name: String) {
        val parentId = _state.value.newFolderIn ?: return
        _state.update { it.copy(newFolderIn = null) }
        scope.launch {
            val result = fileOps.createFolder(parentId, name)
            val targets = picked() ?: return@launch
            val made = result.done.firstOrNull()
            if (made == null) {
                // Back to the sheet with the reason on it. Not a snackbar:
                // see [MoveSheetState.error].
                val why = result.failures.firstOrNull()?.let { FileOpMessages.forFailure(it, "create") }
                _state.update { it.copy(moveSheet = sheetFor(parentId, chosenId = parentId, targets = targets, error = why)) }
                return@launch
            }
            _state.update { it.copy(moveSheet = sheetFor(parentId, chosenId = made.id, targets = targets)) }
        }
    }

    // ── messages ───────────────────────────────────────────────────────

    /**
     * Put a line of the screen's own in the same place (an upload, a
     * poster), so one screen never has two messages fighting for the slot.
     */
    fun say(text: String, failed: Boolean = false) = _state.update { it.copy(message = FileOpMessage(text, failed = failed)) }

    fun clearMessage() = _state.update { it.copy(message = null) }

    private fun report(result: FileOpResult, verb: String, past: String, where: String? = null, undo: UndoMove? = null) {
        val text = FileOpMessages.forResult(result, verb, past, where, pictures)
        // ONE message, always. A failure used to set a banner as well, so the
        // same sentence arrived twice. A failure earns more TIME instead (see
        // the host), not a second copy of itself.
        _state.update { it.copy(message = FileOpMessage(text, undo, failed = !result.ok)) }
    }

    /**
     * True, with the message already up, when [verb] must not start: a
     * picked folder has something taken back out of it, and moving or
     * deleting the folder would take that too. The owner's call was to
     * refuse and say why rather than act on more than was picked; Download
     * honours the exclusions file by file, so it never asks.
     */
    private suspend fun refusedForLeftOut(verb: String): Boolean {
        // One thing acted on by itself has nothing taken out of it.
        if (explicit != null) return false
        val folders = selection.snapshot()?.foldersWithExclusions().orEmpty()
        if (folders.isEmpty()) return false
        val names = folders.mapNotNull { lookup.folder(it.folderId)?.name }
        _state.update { it.copy(message = FileOpMessage(FileOpMessages.forLeftOut(verb, names), failed = true)) }
        return true
    }

    /** A verb is done: a selection it acted on ends; one thing acted on by itself leaves the selection as it was. */
    private fun finishVerb() {
        if (explicit == null) selection.clear()
        explicit = null
    }

    private suspend fun picturesAmong(targets: List<FileOpTarget>): Set<FileOpTarget> =
        targets.filter { it.isOther && lookup.other(it.id)?.name?.let(MediaFileTypes::isPicture) == true }.toSet()

    /**
     * The warning for [file] going (renamed, or deleted along with [going]):
     * it is the picture its folder wears as its poster, and the tile then
     * falls back to the next picture of its own, or a mosaic. Null when it
     * is not the poster.
     */
    private suspend fun posterGoing(file: ShareFileEntity, going: Set<Long>): String? {
        if (!MediaFileTypes.isPicture(file.name)) return null
        val siblings = lookup.othersIn(file.folderId)
        if (FolderPoster.worn(siblings.map { it.name })?.equals(file.name, ignoreCase = true) != true) return null
        val folderName = lookup.folder(file.folderId)?.name?.ifEmpty { null } ?: lookup.shareLabel(file.shareId).substringAfter(" · ")
        val next = FolderPoster.worn(siblings.filterNot { it.id in going }.map { it.name })
        return FileOpMessages.forPosterGoing(folderName, next)
    }

    /** The warning for renaming [file] when it is a video's own picture (`beach.jpg` beside `beach.mp4`); null otherwise. */
    private suspend fun videoPictureOf(file: ShareFileEntity): String? {
        if (!MediaFileTypes.isPicture(file.name)) return null
        val stem = file.name.substringBeforeLast('.')
        val video = lookup.filesUnder(file.folderId).firstOrNull {
            it.folderId == file.folderId && it.name.substringBeforeLast('.').equals(stem, ignoreCase = true)
        } ?: return null
        return FileOpMessages.forVideoPicture(video.name.substringBeforeLast('.'))
    }

    private suspend fun originsOf(targets: List<FileOpTarget>): Map<FileOpTarget, Long> = targets.mapNotNull { target ->
        val origin = when (target.kind) {
            FileOpTarget.Kind.FOLDER -> lookup.folder(target.id)?.parentId
            FileOpTarget.Kind.FILE -> lookup.file(target.id)?.folderId
            FileOpTarget.Kind.OTHER -> lookup.other(target.id)?.folderId
        }
        origin?.let { target to it }
    }.toMap()

    /** The one share every pick is on, or null when they span several. */
    private suspend fun shareOf(targets: List<FileOpTarget>): Long? {
        val shares = targets.mapNotNull { target ->
            when (target.kind) {
                FileOpTarget.Kind.FOLDER -> lookup.folder(target.id)?.shareId
                FileOpTarget.Kind.FILE -> lookup.file(target.id)?.shareId
                FileOpTarget.Kind.OTHER -> lookup.other(target.id)?.shareId
            }
        }.toSet()
        return shares.singleOrNull()
    }

    private suspend fun nameOf(target: FileOpTarget): String? = when (target.kind) {
        FileOpTarget.Kind.FOLDER -> lookup.folder(target.id)?.name
        FileOpTarget.Kind.FILE -> lookup.file(target.id)?.name
        FileOpTarget.Kind.OTHER -> lookup.other(target.id)?.name
    }

    /**
     * The sheet as it looks with [chosenId] picked while looking at [currentId].
     *
     * A picked folder cannot be its own destination, nor can anything
     * inside it, so those rows are offered but not choosable. The test is on
     * PATHS rather than ids because the folder being aimed at may be several
     * levels down from the one that was picked. A folder every pick already
     * sits in is no destination either ("They're already here").
     */
    private suspend fun sheetFor(
        currentId: Long,
        chosenId: Long,
        targets: List<FileOpTarget>,
        error: String? = null,
    ): MoveSheetState? {
        val current = lookup.folder(currentId) ?: return null
        val chosen = lookup.folder(chosenId) ?: return null
        val shareName = lookup.shareLabel(current.shareId).substringAfter(" · ")
        val movingPaths = targets.filter { it.isFolder }.mapNotNull { lookup.folder(it.id)?.relPath }
        fun insideAMovingFolder(relPath: String) =
            movingPaths.any { relPath == it || relPath.startsWith("$it/") }
        fun allAlreadyIn(folder: FolderEntity) = origins.isNotEmpty() && origins.values.all { it == folder.id }
        val currentBlocked = insideAMovingFolder(current.relPath)
        val chosenBlocked = insideAMovingFolder(chosen.relPath)
        return MoveSheetState(
            itemsLabel = FileOpMessages.subjectFor(targets),
            shareName = shareName,
            breadcrumb = (listOf(shareName) + current.relPath.split('/').filter { it.isNotEmpty() }).joinToString(" / "),
            currentFolderId = current.id,
            children = lookup.subfolders(current.id).map { f ->
                val blocked = insideAMovingFolder(f.relPath)
                MoveChild(
                    folderId = f.id,
                    name = f.name,
                    meta = when {
                        blocked -> "Being moved"
                        f.fileCount > 0 -> "${f.fileCount} videos · ${formatBytes(f.byteCount)}"
                        else -> null
                    },
                    enabled = !blocked,
                )
            },
            chosenFolderId = chosen.id,
            chosenName = chosen.name,
            canUp = current.parentId != null,
            currentChoosable = !allAlreadyIn(current) && !currentBlocked,
            confirmEnabled = !allAlreadyIn(chosen) && !chosenBlocked,
            note = when {
                currentBlocked -> "A folder can't move inside itself"
                allAlreadyIn(current) -> "They're already here"
                else -> null
            },
            error = error,
        )
    }
}

/** Everything [FileActions] has on screen, for `FileActionsHost` to draw. */
data class FileActionsState(
    /** Which verbs can work for the picks, and why one cannot. */
    val verbs: FileVerbs = FileVerbs(),
    val renaming: RenameTarget? = null,
    val confirmingDelete: DeleteTarget? = null,
    val moveSheet: MoveSheetState? = null,
    /** The folder a new one would be made in, while the name is being typed. */
    val newFolderIn: Long? = null,
    /** One-shot line for the snackbar, with an Undo when the move can be walked back. */
    val message: FileOpMessage? = null,
)

/**
 * Which of Move, Rename and Delete can work for what is picked, decided
 * before anything is tapped. [hint] explains the verb that is grey, for the
 * pill's detail line.
 */
data class FileVerbs(
    val canMove: Boolean = false,
    val canRename: Boolean = false,
    val canDelete: Boolean = false,
    /** Pictures can be saved to the phone's gallery: a share's, not spoof mode's stand-ins. */
    val canSave: Boolean = false,
    val hint: String? = null,
) {
    companion object {
        /**
         * [shares] is how many shares the picks are on; [readOnly] is why
         * none of them can be changed, when that is so. Nothing is offered on
         * a source with no share behind it, and a move stays within one share
         * (the server cannot rename across them).
         */
        fun of(itemCount: Int, shares: Int, readOnly: ReadOnlySource?): FileVerbs = when {
            itemCount == 0 -> FileVerbs()
            readOnly == ReadOnlySource.DEMO -> FileVerbs(hint = "The demo library can't be changed")
            readOnly == ReadOnlySource.PHONE -> FileVerbs(hint = "Videos on this phone can't be changed here")
            readOnly == ReadOnlySource.SPOOF -> FileVerbs(hint = "Nothing can be changed while spoof mode is on")
            else -> FileVerbs(
                canMove = shares == 1,
                canRename = itemCount == 1,
                canDelete = true,
                canSave = true,
                hint = when {
                    shares > 1 -> "Move works within one share"
                    itemCount > 1 -> "Rename works on one at a time"
                    else -> null
                },
            )
        }
    }
}

/**
 * The one thing a rename is about: a video, a folder or another file.
 * [companions] are renamed along with a video. [picture] says a file is a
 * picture, for the dialog's title; [warning] is what renaming it would
 * change elsewhere — a collection's poster, a video's own picture.
 */
data class RenameTarget(
    val target: FileOpTarget,
    val name: String,
    val companions: Int = 0,
    val picture: Boolean = false,
    val warning: String? = null,
) {
    val isFolder: Boolean get() = target.isFolder
}

/**
 * What a delete would take, named and totalled, so the dialog can say it.
 *
 * [videoCount] and [sizeLabel] count THROUGH picked folders, everything the
 * recursive delete would reach that the app knows about, while [folderCount]
 * counts the folders actually picked. The dialog needs both: one is what is
 * going, the other is what was tapped.
 */
data class DeleteTarget(
    val targets: List<FileOpTarget>,
    val names: List<String>,
    val sizeLabel: String,
    val videoCount: Int,
    val folderCount: Int,
    /** The files going with the picked videos (`Companions`), which the list never shows going. */
    val companionCount: Int = 0,
    /** Files that are not videos, picked in their own right. */
    val otherCount: Int = 0,
    /** Of those, the pictures: all of them makes it a delete of pictures. */
    val pictureCount: Int = 0,
    /** What a collection's tile becomes once its poster goes ([FileOpMessages.forPosterGoing]), when one of these is it. */
    val posterNote: String? = null,
)

/**
 * What just happened, for the snackbar.
 *
 * [undo] is only ever offered for a move, and only when every item made it:
 * the inverse of a move is the same single rename back, which is why it can
 * be offered at all. A delete is a real unlink and has none.
 */
data class FileOpMessage(
    val text: String,
    val undo: UndoMove? = null,
    /** Something did not go through, so it is shown for longer and carries no Undo. */
    val failed: Boolean = false,
)

/** Put these back, each in the folder it came from ([backTo]: folder id to what goes there). */
data class UndoMove(val backTo: Map<Long, List<FileOpTarget>>) {
    companion object {
        /** [done] grouped by where each was before the move; anything with no known origin stays put. */
        fun of(done: List<FileOpTarget>, origins: Map<FileOpTarget, Long>): UndoMove? =
            done.filter { it in origins }.groupBy { origins.getValue(it) }.takeIf { it.isNotEmpty() }?.let(::UndoMove)
    }
}
