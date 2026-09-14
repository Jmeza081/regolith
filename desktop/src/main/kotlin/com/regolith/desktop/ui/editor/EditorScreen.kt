package com.regolith.desktop.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
import com.regolith.desktop.ui.components.ChaptersTextField
import com.regolith.desktop.ui.components.Eyebrow
import com.regolith.desktop.ui.components.PrimaryButton
import com.regolith.desktop.ui.components.ProblemCard
import com.regolith.desktop.ui.components.QuietButton
import com.regolith.desktop.ui.components.SecondaryButton
import com.regolith.desktop.ui.components.clickOnly
import com.regolith.desktop.ui.formatClock
import com.regolith.desktop.ui.formatStart
import com.regolith.desktop.ui.theme.Palette
import com.regolith.domain.playback.Chapter
import com.regolith.domain.playback.ChapterDraft

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
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                QuietButton("‹ Back", onBack, Modifier.testTag("editor_back_button"))
                Text(state.title, style = MaterialTheme.typography.titleMedium, color = Palette.Ink)
            }
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.Black).testTag("editor_video")) {
                videoSurface(player)
            }
            player.error?.let { Text(it, color = Palette.Red) }
            Transport(player)
            state.draft?.let { d -> ChapterStrip(d, player.durationMs, onSelect = vm::select) }
            Text(
                "Space play · M mark · ←/→ 5 s (⇧ ½ s) · ⌘S save · Esc close",
                style = MaterialTheme.typography.bodySmall,
                color = Palette.Metadata,
            )
        }
        Column(
            Modifier.weight(1f).fillMaxHeight().background(Palette.Surface).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Eyebrow("Chapters")
            val problem = state.problem
            val draft = state.draft
            when {
                problem != null -> ProblemCard(problem, Modifier.testTag("editor_problem"))
                state.loading || draft == null -> CircularProgressIndicator(color = Palette.Body)
                else -> {
                    Text(
                        state.message ?: if (state.hasSidecar) "From the film's chapter file" else "No chapter file yet",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (state.message?.startsWith("Not ") == true) Palette.Red else Palette.Body,
                        modifier = Modifier.testTag("editor_message"),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (player.durationMs == 0L && player.error != null) {
                            // No video to mark from: add one and type its Start.
                            SecondaryButton("Add chapter", vm::addChapter, modifier = Modifier.testTag("editor_add_button"))
                        } else {
                            SecondaryButton("Mark here", vm::mark, enabled = player.durationMs > 0, modifier = Modifier.testTag("editor_mark_button"))
                        }
                        PrimaryButton("Save", { vm.save() }, enabled = state.canSave, loading = state.saving, modifier = Modifier.testTag("editor_save_button"))
                    }
                    LazyColumn(Modifier.weight(1f).testTag("editor_chapter_list"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        itemsIndexed(draft.marks) { i, _ -> ChapterRow(draft, i, vm, ::refocus) }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        QuietButton("Remove all chapters", vm::clearAll, Modifier.testTag("editor_clear_all_button"), enabled = state.canClearAll, color = Palette.Red)
                        if (state.hasSidecar) {
                            QuietButton("Revert chapters", vm::askRevert, Modifier.testTag("editor_revert_button"), enabled = !state.reverting && !state.saving, color = Palette.Red)
                        }
                    }
                    Eyebrow("The file Save writes")
                    Text(
                        state.preview,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = Palette.Body,
                        modifier = Modifier.height(120.dp).verticalScroll(rememberScrollState()).testTag("editor_preview"),
                    )
                }
            }
        }
    }

    if (state.revertAsked) {
        val n = state.draft?.marks?.size ?: 0
        AlertDialog(
            onDismissRequest = vm::cancelRevert,
            title = { Text("Revert chapters?") },
            text = {
                Text(
                    "Your " + (if (n == 1) "chapter" else "$n chapters") + " on this film go, and the chapter file beside it on the share. " +
                        "The phone goes back to the film's own markers, or the even split.",
                )
            },
            confirmButton = { PrimaryButton("Revert", vm::confirmRevert, Modifier.testTag("editor_revert_confirm_button")) },
            dismissButton = { QuietButton("Keep mine", vm::cancelRevert, Modifier.testTag("editor_revert_keep_button")) },
            containerColor = Palette.Surface,
            titleContentColor = Palette.Ink,
            textContentColor = Palette.Body,
        )
    }

    guard.pending?.let { leave ->
        AlertDialog(
            onDismissRequest = { guard.pending = null },
            title = { Text("Discard changes?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("What you marked and named since the last save goes. The chapter file on the share stays as it was.")
                    state.message?.takeIf { it.startsWith("Not saved") }?.let { Text(it, color = Palette.Red) }
                }
            },
            confirmButton = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    QuietButton("Discard", { guard.pending = null; leave() }, Modifier.testTag("editor_discard_button"))
                    PrimaryButton(
                        "Save", { vm.save(onSaved = { guard.pending = null; leave() }) },
                        loading = state.saving, modifier = Modifier.testTag("editor_discard_save_button"),
                    )
                }
            },
            dismissButton = { QuietButton("Keep editing", { guard.pending = null }, Modifier.testTag("editor_keep_editing_button")) },
            containerColor = Palette.Surface,
            titleContentColor = Palette.Ink,
            textContentColor = Palette.Body,
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
private fun Transport(player: FilmPlayer) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        SecondaryButton(if (player.playing) "Pause" else "Play", player::togglePause, modifier = Modifier.testTag("editor_play_button"))
        Text(
            "${formatClock(player.positionMs)} / ${formatClock(player.durationMs)}",
            style = MaterialTheme.typography.bodyMedium,
            color = Palette.Body,
            modifier = Modifier.testTag("editor_clock"),
        )
        Slider(
            value = if (player.durationMs > 0) (player.positionMs.toFloat() / player.durationMs).coerceIn(0f, 1f) else 0f,
            onValueChange = { player.seekTo((it * player.durationMs).toLong()) },
            enabled = player.durationMs > 0,
            colors = SliderDefaults.colors(thumbColor = Palette.Ink, activeTrackColor = Palette.Ink, inactiveTrackColor = Palette.TrackWhite),
            // A focused slider would eat ←/→ for its own 1% steps.
            modifier = Modifier.weight(1f).clickOnly().testTag("editor_scrubber"),
        )
    }
}

/** The chapters as segments of the runtime; the open one is lit. Click one to open it. */
@Composable
private fun ChapterStrip(draft: ChapterDraft, durationMs: Long, onSelect: (Int) -> Unit) {
    if (durationMs <= 0) return
    Row(Modifier.fillMaxWidth().height(20.dp).testTag("editor_chapter_strip")) {
        draft.marks.forEachIndexed { i, m ->
            val end = draft.marks.getOrNull(i + 1)?.startMs ?: durationMs
            val share = ((end - m.startMs).toFloat() / durationMs).coerceAtLeast(0.002f)
            Box(
                Modifier
                    .weight(share)
                    .fillMaxHeight()
                    .padding(horizontal = 1.dp)
                    .background(if (draft.selected == i) Palette.Ink else Palette.Raised)
                    .clickOnly()
                    .clickable { onSelect(i) },
            )
        }
    }
}

@Composable
private fun ChapterRow(draft: ChapterDraft, index: Int, vm: EditorViewModel, refocus: () -> Unit) {
    val mark = draft.marks[index]
    val open = draft.selected == index
    // As on the phone: while one row is open, the others are locked.
    val locked = draft.selected != null && !open
    Column(
        Modifier
            .fillMaxWidth()
            .background(if (open) Palette.Lifted else Color.Transparent, RoundedCornerShape(10.dp))
            .then(if (open) Modifier.border(1.dp, Palette.LiftedBorder, RoundedCornerShape(10.dp)) else Modifier)
            .clickOnly()
            .clickable(enabled = !locked) { vm.select(if (open) null else index) }
            .testTag("editor_chapter_row_$index")
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(formatStart(mark.startMs), Modifier.width(72.dp), style = MaterialTheme.typography.bodyMedium, color = if (locked) Palette.DisabledInk else Palette.Body)
            Text(mark.label(index), style = MaterialTheme.typography.bodyLarge, color = if (locked) Palette.DisabledInk else Palette.Ink)
        }
        if (open) {
            ChaptersTextField(
                value = mark.title ?: "",
                onValueChange = { vm.rename(index, it) },
                label = "Name",
                placeholder = "Part ${index + 1}",
                modifier = Modifier.testTag("editor_name_field").onPreviewKeyEvent { e ->
                    // Enter finishes naming, like Done.
                    if (e.type == KeyEventType.KeyDown && e.key == Key.Enter) { vm.select(null); refocus(); true } else false
                },
            )
            if (index == 0) {
                Text("The film starts here; this one stays at 0:00.", style = MaterialTheme.typography.bodySmall, color = Palette.Metadata)
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
                if (index > 0) QuietButton("Delete", { vm.remove(index) }, Modifier.testTag("editor_delete_button"))
                QuietButton("Done", { vm.select(null); refocus() }, Modifier.testTag("editor_done_button"))
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
    ChaptersTextField(
        value = text,
        onValueChange = { text = it; error = null },
        label = "Start",
        placeholder = "0:12:30",
        isError = error != null,
        supportingText = error,
        modifier = Modifier
            .testTag("editor_start_field")
            .onPreviewKeyEvent { e -> if (e.type == KeyEventType.KeyDown && e.key == Key.Enter) { commit(); true } else false }
            .onFocusChanged { f ->
                if (hadFocus && !f.isFocused) commit()
                hadFocus = f.isFocused
            },
    )
}

/** One of four equal nudge buttons; the row is shared by weight so they never overflow a narrow panel. */
@Composable
private fun RowScope.NudgeButton(label: String, tag: String, onClick: () -> Unit) {
    SecondaryButton(label, onClick, modifier = Modifier.weight(1f).testTag(tag))
}
