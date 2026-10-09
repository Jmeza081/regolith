package com.regolith.ui.poster

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import com.composables.icons.lucide.R as LucideR
import com.regolith.domain.artwork.Dimensions
import com.regolith.domain.artwork.PosterFraming
import com.regolith.domain.playback.ChapterDraft
import com.regolith.ui.components.Eyebrow
import com.regolith.ui.components.PillButton
import com.regolith.ui.components.PosterCropper
import com.regolith.ui.components.PosterFramingSaver
import com.regolith.ui.components.PosterFramingScaffold
import com.regolith.ui.components.PosterSwapSheet
import com.regolith.ui.components.PrimaryButton
import com.regolith.ui.components.RegolithTextField
import com.regolith.ui.components.Scrubber
import com.regolith.ui.components.SecondaryButton
import com.regolith.ui.components.StrataLoader
import com.regolith.ui.theme.RegolithTheme
import com.regolith.ui.theme.Spacing
import com.regolith.ui.theme.TextStyles
import com.regolith.ui.util.formatClock
import java.util.Locale

/**
 * The poster editor (a pushed screen, opened from the player's Playback sheet,
 * "Make a poster from this frame"):
 * choose a frame by scrubbing, typing a time or stepping a frame at a time,
 * frame it in the fixed 2:3 box by dragging and pinching the picture, and
 * save it as poster.jpg in the film's folder. A poster already there is
 * kept under a dated name, after the sheet that shows both
 * ([PosterSwapSheet]).
 *
 * The layout is [PosterFramingScaffold]'s: the picture full-bleed, the
 * chrome on it as frosted glass, and the crop box fitted into whatever the
 * glass leaves uncovered.
 */
@UnstableApi
@Composable
fun PosterEditorScreen(
    viewModel: PosterEditorViewModel,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // The framing is the screen's, like a scroll position (see
    // [PosterEditorUiState]). Saveable, so rotating the phone mid-edit keeps it.
    var framing by rememberSaveable(stateSaver = PosterFramingSaver) { mutableStateOf(PosterFraming()) }
    val image = remember(state.frame) { state.frame?.asImageBitmap() }

    LaunchedEffect(state.done) { if (state.done) onClose() }

    val save = {
        val frame = state.frame
        if (frame != null) {
            // The crop is worked out against the box as it is on screen now;
            // cropRect only cares about its shape, which is always 2:3.
            val box = PosterFraming.boxIn(PosterFraming.ASPECT * 1000f, 1000f)
            viewModel.save(framing.cropRect(Dimensions(frame.width.toFloat(), frame.height.toFloat()), box))
        }
    }

    PosterFramingScaffold(
        title = "Poster",
        subtitle = state.title.ifEmpty { null },
        onClose = onClose,
        modifier = modifier.testTag("poster_editor_screen"),
        picture = { covered ->
            PosterCropper(image = image, framing = framing, onFramingChange = { framing = it }, contentPadding = covered, modifier = Modifier.fillMaxSize())
        },
        status = {
            if (state.loadingFrame) {
                StrataLoader(height = 32.dp, testTag = "poster_frame_loading")
            } else if (state.frameFailed) {
                Text(
                    "Couldn’t read this frame. Try another time.",
                    style = TextStyles.notice, color = RegolithTheme.colors.inkSoft,
                    modifier = Modifier.background(RegolithTheme.colors.overArt).padding(Spacing.s12).testTag("poster_frame_failed"),
                )
            }
        },
    ) {
        Controls(state, viewModel, onSave = save, onClose = onClose)
    }

    state.swap?.let { swap ->
        PosterSwapSheet(swap, onConfirm = viewModel::confirmSwap, onDismiss = viewModel::dismissSwap, busy = state.saving)
    }
}

@UnstableApi
@Composable
private fun ColumnScope.Controls(
    state: PosterEditorUiState,
    viewModel: PosterEditorViewModel,
    onSave: () -> Unit,
    onClose: () -> Unit,
) {
    val colors = RegolithTheme.colors
    val focus = LocalFocusManager.current
    // While a finger is on the scrubber the clock follows it; the frame is
    // only decoded where the finger lets go (decoding every position on the
    // way would queue seconds of work nobody wants).
    var scrubMs by remember { mutableStateOf<Long?>(null) }
    val shownMs = scrubMs ?: state.positionMs
    var text by remember(state.positionMs) { mutableStateOf(clockWithTenths(state.positionMs)) }
    var textError by remember { mutableStateOf(false) }
    val commitText = {
        val ms = ChapterDraft.parseClock(text)
        textError = ms == null || (state.durationMs > 0 && ms >= state.durationMs)
        if (!textError && ms != null) viewModel.seekTo(ms)
    }

    Eyebrow("Frame")
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.s12)) {
        RegolithTextField(
            value = if (scrubMs != null) clockWithTenths(shownMs) else text,
            onValueChange = { text = it; textError = false },
            label = "Time",
            placeholder = "0:42:17",
            isError = textError,
            testTag = "poster_time_field",
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { commitText(); focus.clearFocus() }),
            modifier = Modifier.weight(1f).onFocusChanged { if (!it.isFocused && text != clockWithTenths(state.positionMs)) commitText() },
        )
        if (state.durationMs > 0) Text("/ ${formatClock(state.durationMs)}", style = TextStyles.buttonSmall, color = colors.body)
    }
    if (textError) Text("Use 12:30, 0:12:30 or 1:02:15.5, within the film", style = TextStyles.meta12, color = colors.accent)
    if (state.durationMs > 0) {
        Scrubber(
            progress = { (shownMs.toFloat() / state.durationMs).coerceIn(0f, 1f) },
            durationMs = state.durationMs,
            buffered = { 0f },
            onScrubStart = { scrubMs = state.positionMs },
            onScrub = { f -> scrubMs = (f * state.durationMs).toLong() },
            onScrubEnd = { f -> scrubMs = null; viewModel.seekTo((f * state.durationMs).toLong()) },
            testTag = "poster_scrubber",
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s8), modifier = Modifier.fillMaxWidth()) {
        PillButton("−10s", { viewModel.seekBy(-10_000) }, "poster_back_10s", Modifier.weight(1f), onMedia = false, fill = true)
        PillButton("−1 frame", { viewModel.stepFrames(-1) }, "poster_back_frame", Modifier.weight(1f), onMedia = false, fill = true)
        PillButton("+1 frame", { viewModel.stepFrames(1) }, "poster_forward_frame", Modifier.weight(1f), onMedia = false, fill = true)
        PillButton("+10s", { viewModel.seekBy(10_000) }, "poster_forward_10s", Modifier.weight(1f), onMedia = false, fill = true)
    }

    state.target?.let { target ->
        Text(
            "Becomes the poster for ${target.folderName}, saved as poster.jpg in that folder.",
            style = TextStyles.settingMeta, color = colors.metadata, modifier = Modifier.padding(top = Spacing.s8).testTag("poster_destination"),
        )
    }
    state.saveError?.let { Text(it, style = TextStyles.notice, color = colors.accent, modifier = Modifier.testTag("poster_save_error")) }
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s12), modifier = Modifier.fillMaxWidth()) {
        SecondaryButton("Cancel", onClose, "poster_cancel", Modifier.weight(1f))
        PrimaryButton(
            "Save poster", onSave, "poster_save",
            modifier = Modifier.weight(2f),
            enabled = state.canSave,
            loading = state.saving && state.swap == null,
            leadingIcon = painterResource(LucideR.drawable.lucide_ic_save),
        )
    }
}

/** `42:17.3`: the player's clock plus tenths, the finest [ChapterDraft.parseClock] reads back. */
private fun clockWithTenths(ms: Long): String = formatClock(ms) + String.format(Locale.US, ".%d", (ms % 1000) / 100)
