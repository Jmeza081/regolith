package com.regolith.ui.components

import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.regolith.R
import com.regolith.domain.fileops.FileNames
import com.regolith.domain.fileops.FileOpTarget
import com.regolith.ui.util.FileActions
import com.regolith.ui.util.FileOpMessages
import com.regolith.ui.util.SelectionUiState

/**
 * Everything [FileActions] puts on screen: the rename prompt, the delete
 * confirm, the move sheet with its new-folder prompt, and the message that
 * reports how it went (with Undo after a move). Draw it once per screen that
 * owns a [FileActions], anywhere in the screen's box: the dialogs and the
 * sheet are windows of their own, and the message goes to the app's one
 * snackbar host.
 *
 * Web analogy: the `<FileActionModals/>` that sits beside a page and renders
 * whichever modal the hook's state says is open.
 *
 * [tagPrefix] starts every test tag, "browse" or "library", so automation
 * can tell which screen a dialog belongs to (`browse_rename`, `library_delete`).
 */
@Composable
fun FileActionsHost(actions: FileActions, tagPrefix: String) {
    val state by actions.state.collectAsStateWithLifecycle()

    state.renaming?.let { target ->
        // A folder has no extension to protect, so the whole name is the
        // field; a video keeps its suffix outside it, where it cannot be
        // typed away.
        val ext = if (target.isFolder) "" else target.name.substringAfterLast('.', "")
        PromptDialog(
            title = when (target.target.kind) {
                FileOpTarget.Kind.FOLDER -> "Rename folder"
                FileOpTarget.Kind.FILE -> "Rename video"
                FileOpTarget.Kind.OTHER -> "Rename file"
            },
            label = "Name",
            initialValue = if (target.isFolder) target.name else FileNames.baseOf(target.name),
            confirmLabel = "Rename",
            onConfirm = actions::rename,
            onCancel = actions::cancelRename,
            testTag = "${tagPrefix}_rename",
            note = FileOpMessages.forRenameNote(target.target.kind, ext, target.companions),
            maxLength = FileNames.MAX_BASE,
        )
    }

    state.confirmingDelete?.let { target ->
        ConfirmDialog(
            title = FileOpMessages.deleteTitle(target),
            body = FileOpMessages.deleteBody(target),
            confirmLabel = FileOpMessages.deleteConfirmLabel(target),
            keepLabel = if (target.targets.size == 1) "Keep it" else "Keep them",
            onConfirm = actions::confirmDelete,
            onKeep = actions::cancelDelete,
            testTag = "${tagPrefix}_delete",
        )
    }

    state.moveSheet?.let { sheet ->
        MoveToSheet(
            state = sheet,
            onUp = actions::moveUp,
            onOpen = actions::moveWalk,
            onChoose = actions::moveChoose,
            onNewFolder = actions::startNewFolder,
            onConfirm = actions::confirmMove,
            onDismiss = actions::dismissMove,
        )
    }

    // Over the sheet rather than instead of it: the folder is being made as
    // an answer to "where?", so the picker stays open behind and comes back
    // with the new folder already chosen.
    if (state.newFolderIn != null) {
        PromptDialog(
            title = "New folder",
            label = "Name",
            initialValue = "",
            placeholder = "Season 02",
            confirmLabel = "Create",
            onConfirm = actions::createFolder,
            onCancel = actions::cancelNewFolder,
            testTag = "${tagPrefix}_new_folder",
            note = "Made on the share, inside ${state.moveSheet?.breadcrumb?.substringAfterLast(" / ") ?: "this folder"}.",
            maxLength = FileNames.MAX_BASE,
        )
    }

    // One host for the whole app, drawn by the chrome above the pill.
    val snackbar = LocalAppSnackbar.current
    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        // A failure stays up longer (showMessage's default for FAILED), and
        // the ring around its icon shows the longer life running down.
        val result = snackbar.showMessage(
            message.text,
            kind = if (message.failed) MessageKind.FAILED else MessageKind.DONE,
            actionLabel = if (message.undo != null) "Undo" else null,
        )
        if (result == SnackbarResult.ActionPerformed) actions.undoMove() else actions.clearMessage()
    }
}

/**
 * Turn the nav pill into [selection]'s toolbar while one is running:
 * Download, Move, Rename and Delete, the same four in the same order on every
 * screen that picks things, each greyed when [FileActions] says it cannot
 * work. Cancel is drawn by the pill itself, in the cell where Home sits.
 *
 * [here] is the folder on screen, where the move sheet opens (null on a wall
 * with no folder of its own). The verbs' test tags are
 * `<tagPrefix>_select_download` and so on.
 */
@Composable
fun FileSelectionChrome(
    selection: SelectionUiState?,
    actions: FileActions,
    here: Long?,
    tagPrefix: String,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
) {
    val verbs by actions.state.collectAsStateWithLifecycle()
    val chrome = LocalSelectionChrome.current
    DisposableEffect(selection, verbs.verbs, here) {
        val live = selection
        if (live == null) {
            chrome.clear()
        } else {
            val can = verbs.verbs
            chrome.show(
                SelectionChromeState(
                    verbs = listOf(
                        SelectionVerb("Download", R.drawable.rg_ic_download, onDownload, "${tagPrefix}_select_download", enabled = live.canDownload),
                        SelectionVerb("Move", R.drawable.rg_ic_folder_go, { actions.startMove(here) }, "${tagPrefix}_select_move", enabled = can.canMove),
                        SelectionVerb("Rename", R.drawable.rg_ic_rename, actions::startRename, "${tagPrefix}_select_rename", enabled = can.canRename),
                        SelectionVerb("Delete", R.drawable.rg_ic_trash, actions::startDelete, "${tagPrefix}_select_delete", enabled = can.canDelete, destructive = true),
                    ),
                    onCancel = onCancel,
                    summary = live.summary,
                    // The hint explains a GREY verb, so it outranks the
                    // selection's own qualifier when both apply.
                    detail = can.hint ?: live.detail,
                ),
            )
        }
        onDispose { chrome.clear() }
    }
}
