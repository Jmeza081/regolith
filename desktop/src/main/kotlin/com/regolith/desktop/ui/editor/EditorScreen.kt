package com.regolith.desktop.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.regolith.desktop.AppGraph
import com.regolith.desktop.navigation.Route
import com.regolith.desktop.player.FilmPlayer
import com.regolith.desktop.ui.LeaveGuard
import com.regolith.desktop.ui.components.clickOnly
import com.regolith.desktop.ui.formatClock
import com.regolith.desktop.ui.formatStart
import com.regolith.domain.playback.Chapter
import com.regolith.domain.playback.ChapterDraft
import com.regolith.ui.components.ConfirmDialog
import com.regolith.ui.components.ErrorCard
import com.regolith.ui.components.Eyebrow
import com.regolith.ui.components.PillButton
import com.regolith.ui.components.PrimaryButton
import com.regolith.ui.components.RegolithTextField
import com.regolith.ui.components.Scrubber
import com.regolith.ui.components.SecondaryButton
import com.regolith.ui.components.TertiaryButton
import com.regolith.ui.components.TopBar
import com.regolith.ui.theme.RegolithTheme
import com.regolith.ui.theme.TextStyles

/**
 * One film and its chapters: the picture and transport on the left, the
 * chapter list on the right. [videoSurface] draws the picture; the app
 * passes VLC's Swing view, tests pass a plain box.
 *
 * Keyboard (see [onEditorKey]): Space play/pause, M mark here, ←/→ 5 s
 * (Shift: ½ s), ⌘S save, Esc close the open row. The keys go to the
 * screen's root, so controls here never take focus on click; inside a text
 * field keys type, and Esc hands focus back.
 *
 * [guard] is told while there are unsaved changes, and asks here before
 * Back or closing the window drops them.
 */
@Composable
fun EditorScreen(
    graph: AppGraph,
    route: Route.Editor,
    player: FilmPlayer,
    videoSurface: @Composable (FilmPlayer) -> Unit,
    guard: LeaveGuard,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val vm = remember(route) { EditorViewModel(graph, route, scope, player) }
    val state by vm.state.collectAsState()
    val rootFocus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    fun refocus() {
        focusManager.clearFocus()
        runCatching { rootFocus.requestFocus() }
    }

    DisposableEffect(route) {
        onDispose {
            player.release()
            guard.unsaved = false
            guard.pending = null
        }
    }
    SideEffect { guard.unsaved = state.draft?.dirty == true }
    LaunchedEffect(player.durationMs) { vm.onDurationKnown(player.durationMs) }
    // Whenever no row is open, the shortcuts should work without a click first.
    LaunchedEffect(state.draft?.selected, state.loading) { if (state.draft?.selected == null) runCatching { rootFocus.requestFocus() } }

    Row(
        Modifier
            .fillMaxSize()
            .focusRequester(rootFocus)
            .focusable()
            .onKeyEvent { onEditorKey(it, state, vm, player, ::refocus) }
            .testTag("editor_root"),
    ) {
        Column(Modifier.weight(2f).fillMaxHeight().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TopBar(title = state.title, onBack = onBack, backTestTag = "editor_back_button", statusBarPadding = false)
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.Black).testTag("editor_video")) {
                videoSurface(player)
            }
            player.error?.let { Text(it, style = TextStyles.body, color = RegolithTheme.colors.accent) }
            Transport(player, state.draft?.marks.orEmpty())
            Text(
                "Space play · M mark · ←/→ 5 s (⇧ ½ s) · ⌘S save · Esc close",
                style = TextStyles.meta,
                color = RegolithTheme.colors.metadata,
            )
        }
        Column(
            Modifier.weight(1f).fillMaxHeight().background(RegolithTheme.colors.surface).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Eyebrow("Chapters")
            val problem = state.problem
            val draft = state.draft
            when {
                problem != null -> ErrorCard(problem.message, testTag = "editor_problem", detail = problem.detail)
                state.loading || draft == null -> CircularProgressIndicator(color = RegolithTheme.colors.body)
                else -> {
                    Text(
                        state.message ?: state.statusLine,
                        style = TextStyles.meta,
                        color = if (state.message?.startsWith("Not ") == true) RegolithTheme.colors.accent else RegolithTheme.colors.body,
                        modifier = Modifier.testTag("editor_message"),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (player.durationMs == 0L && player.error != null) {
                            // No video to mark from: add one and type its Start.
                            SecondaryButton("Add chapter", vm::addChapter, testTag = "editor_add_button", compact = true, modifier = Modifier.clickOnly())
                        } else {
                            SecondaryButton("Mark here", vm::mark, testTag = "editor_mark_button", enabled = player.durationMs > 0, compact = true, modifier = Modifier.clickOnly())
                        }
                        PrimaryButton("Save", { vm.save() }, testTag = "editor_save_button", enabled = state.canSave, loading = state.saving, compact = true, modifier = Modifier.clickOnly())
                    }
                    LazyColumn(Modifier.weight(1f).testTag("editor_chapter_list"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        itemsIndexed(draft.marks) { i, _ -> ChapterRow(draft, i, state.folderNames, vm, ::refocus) }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        TertiaryButton("Remove all chapters", vm::clearAll, testTag = "editor_clear_all_button", enabled = state.canClearAll, ink = RegolithTheme.colors.accent, modifier = Modifier.clickOnly())
                        if (state.hasSidecar) {
                            TertiaryButton("Revert chapters", vm::askRevert, testTag = "editor_revert_button", enabled = !state.reverting && !state.saving, ink = RegolithTheme.colors.accent, modifier = Modifier.clickOnly())
                        }
                    }
                    Eyebrow("The file Save writes")
                    Text(
                        state.preview,
                        style = TextStyles.meta.copy(fontFamily = FontFamily.Monospace),
                        color = RegolithTheme.colors.body,
                        modifier = Modifier.height(120.dp).verticalScroll(rememberScrollState()).testTag("editor_preview"),
                    )
                }
            }
        }
    }

    if (state.revertAsked) {
        val n = state.draft?.marks?.size ?: 0
        ConfirmDialog(
            title = "Revert chapters?",
            body = "Your " + (if (n == 1) "chapter" else "$n chapters") + " on this film go, and the chapter file beside it on the share. " +
                "The phone goes back to the film's own markers, or the even split.",
            confirmLabel = "Revert",
            keepLabel = "Keep mine",
            onConfirm = vm::confirmRevert,
            onKeep = vm::cancelRevert,
            testTag = "editor_revert",
        )
    }

    guard.pending?.let { leave ->
        // Discarding is the destructive way out, so it is the red one; Save is
        // the frosted third way, and Keep editing stays where it always is.
        ConfirmDialog(
            title = "Discard changes?",
            body = "What you marked and named since the last save goes. The chapter file on the share stays as it was.",
            confirmLabel = "Discard",
            keepLabel = "Keep editing",
            onConfirm = { guard.pending = null; leave() },
            onKeep = { guard.pending = null },
            testTag = "editor_leave",
            note = state.message?.takeIf { it.startsWith("Not saved") },
            alternateLabel = if (state.saving) "Saving…" else "Save",
            onAlternate = { vm.save(onSaved = { guard.pending = null; leave() }) },
            alternateEnabled = !state.saving,
        )
    }
}

/**
 * The editor's shortcuts. Runs for keys that nothing focused inside the
 * screen used first, so typing in a field is never hijacked; ⌘S and Esc
 * still arrive from a field because text fields do not use them.
 */
private fun onEditorKey(e: KeyEvent, state: EditorUiState, vm: EditorViewModel, player: FilmPlayer, refocus: () -> Unit): Boolean {
    if (e.type != KeyEventType.KeyDown) return false
    val step = if (e.isShiftPressed) ChapterDraft.NUDGE_FINE_MS else ChapterDraft.NUDGE_COARSE_MS
    return when {
        e.isMetaPressed && e.key == Key.S -> { if (state.canSave) vm.save(); true }
        e.isMetaPressed || e.isCtrlPressed || e.isAltPressed -> false
        e.key == Key.Escape -> { vm.select(null); refocus(); true }
        state.draft == null -> false
        e.key == Key.Spacebar -> { player.togglePause(); true }
        e.key == Key.M -> {
            if (player.durationMs > 0) vm.mark() else if (player.error != null) vm.addChapter()
            true
        }
        e.key == Key.DirectionLeft -> { player.seekTo(player.positionMs - step); true }
        e.key == Key.DirectionRight -> { player.seekTo(player.positionMs + step); true }
        else -> false
    }
}

@Composable
private fun Transport(player: FilmPlayer, chapters: List<Chapter>) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        SecondaryButton(
            if (player.playing) "Pause" else "Play", player::togglePause, testTag = "editor_play_button", compact = true,
            // A fixed width, so the scrubber does not shift when the label changes.
            modifier = Modifier.width(88.dp).clickOnly(),
        )
        Text(
            "${formatClock(player.positionMs)} / ${formatClock(player.durationMs)}",
            style = TextStyles.body,
            color = RegolithTheme.colors.body,
            modifier = Modifier.testTag("editor_clock"),
        )
        // The phone's scrubber: chapters cut the track, and it takes no focus,
        // so ←/→ still reach the editor's shortcuts.
        Scrubber(
            progress = { if (player.durationMs > 0) (player.positionMs.toFloat() / player.durationMs).coerceIn(0f, 1f) else 0f },
            durationMs = player.durationMs,
            buffered = { 0f },
            onScrubStart = {},
            onScrub = { f -> player.seekTo((f * player.durationMs).toLong()) },
            onScrubEnd = { f -> player.seekTo((f * player.durationMs).toLong()) },
            chapters = chapters,
            modifier = Modifier.weight(1f),
            testTag = "editor_scrubber",
        )
    }
}

@Composable
private fun ChapterRow(draft: ChapterDraft, index: Int, folderNames: List<String>, vm: EditorViewModel, refocus: () -> Unit) {
    val mark = draft.marks[index]
    val open = draft.selected == index
    // As on the phone: while one row is open, the others are locked.
    val locked = draft.selected != null && !open
    Column(
        Modifier
            .fillMaxWidth()
            .background(if (open) RegolithTheme.colors.lifted else Color.Transparent, RoundedCornerShape(10.dp))
            .then(if (open) Modifier.border(1.dp, RegolithTheme.colors.liftedBorder, RoundedCornerShape(10.dp)) else Modifier)
            .clickOnly()
            .clickable(enabled = !locked) { vm.select(if (open) null else index) }
            .testTag("editor_chapter_row_$index")
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(formatStart(mark.startMs), Modifier.width(72.dp), style = TextStyles.body, color = if (locked) RegolithTheme.colors.disabledInk else RegolithTheme.colors.body)
            Text(mark.label(index), style = TextStyles.settingLabel, color = if (locked) RegolithTheme.colors.disabledInk else RegolithTheme.colors.ink)
        }
        if (open) {
            RegolithTextField(
                value = mark.title ?: "",
                onValueChange = { vm.rename(index, it) },
                label = "Name",
                testTag = "editor_name_field",
                placeholder = "Part ${index + 1}",
                modifier = Modifier.onPreviewKeyEvent { e ->
                    // Enter finishes naming, like Done.
                    if (e.type == KeyEventType.KeyDown && e.key == Key.Enter) { vm.select(null); refocus(); true } else false
                },
            )
            val suggestions = remember(folderNames, mark.title) { NameSuggestions.rank(folderNames, mark.title.orEmpty()) }
            NameSuggestionChips(suggestions) { vm.rename(index, it) }
            if (index == 0) {
                Text("The film starts here; this one stays at 0:00.", style = TextStyles.meta, color = RegolithTheme.colors.metadata)
            } else {
                StartField(mark, index, vm)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    NudgeButton("−5 s", "editor_nudge_minus5") { vm.nudge(index, -ChapterDraft.NUDGE_COARSE_MS) }
                    NudgeButton("−½ s", "editor_nudge_minus") { vm.nudge(index, -ChapterDraft.NUDGE_FINE_MS) }
                    NudgeButton("+½ s", "editor_nudge_plus") { vm.nudge(index, ChapterDraft.NUDGE_FINE_MS) }
                    NudgeButton("+5 s", "editor_nudge_plus5") { vm.nudge(index, ChapterDraft.NUDGE_COARSE_MS) }
                }
            }
            // As on the phone: Delete and Done sit on their own row, to the right.
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                if (index > 0) TertiaryButton("Delete", { vm.remove(index) }, testTag = "editor_delete_button", ink = RegolithTheme.colors.accent, modifier = Modifier.clickOnly())
                TertiaryButton("Done", { vm.select(null); refocus() }, testTag = "editor_done_button", modifier = Modifier.clickOnly())
            }
        }
    }
}

/** The typed start time. Commits on Enter or when focus leaves, never per keystroke, as on the phone. */
@Composable
private fun StartField(mark: Chapter, index: Int, vm: EditorViewModel) {
    var text by remember(mark.startMs) { mutableStateOf(formatStart(mark.startMs)) }
    var error by remember(index) { mutableStateOf<String?>(null) }
    var hadFocus by remember { mutableStateOf(false) }
    fun commit() {
        error = if (text.trim() == formatStart(mark.startMs)) null else vm.typeStart(index, text)
    }
    RegolithTextField(
        value = text,
        onValueChange = { text = it; error = null },
        label = "Start",
        testTag = "editor_start_field",
        placeholder = "0:12:30",
        isError = error != null,
        // The modifier sits on the field's card, around the text input, so it
        // sees the input's keys first and learns whether focus is inside it.
        modifier = Modifier
            .onPreviewKeyEvent { e -> if (e.type == KeyEventType.KeyDown && e.key == Key.Enter) { commit(); true } else false }
            .onFocusChanged { f ->
                if (hadFocus && !f.hasFocus) commit()
                hadFocus = f.hasFocus
            },
    )
    // As on the phone, the reason sits under the field in red.
    error?.let { Text(it, style = TextStyles.meta, color = RegolithTheme.colors.accent, modifier = Modifier.testTag("editor_start_error")) }
}

/** One of four equal nudge buttons; the row is shared by weight so they never overflow a narrow panel. */
@Composable
private fun RowScope.NudgeButton(label: String, tag: String, onClick: () -> Unit) {
    SecondaryButton(label, onClick, testTag = tag, compact = true, modifier = Modifier.weight(1f).clickOnly())
}

/** Names already used in this folder's chapter files; clicking one names the open chapter. */
@Composable
private fun NameSuggestionChips(names: List<String>, onPick: (String) -> Unit) {
    if (names.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.testTag("editor_suggestions")) {
        Text("Used in this folder", style = TextStyles.meta, color = RegolithTheme.colors.metadata)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            names.forEach { name ->
                // The phone's suggestion pills, off the picture.
                PillButton(
                    text = name, onClick = { onPick(name) }, testTag = "editor_suggestion_" + name.lowercase().replace(' ', '_'),
                    onMedia = false, modifier = Modifier.clickOnly(),
                )
            }
        }
    }
}
