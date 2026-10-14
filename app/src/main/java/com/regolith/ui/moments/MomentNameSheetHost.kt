package com.regolith.ui.moments

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.regolith.ui.components.MomentNameSheet
import com.regolith.ui.util.formatClock
import com.regolith.ui.util.formatDurationShort

/**
 * The quick name sheet for whatever [namer] has open, or nothing when it has
 * nothing open. The screen calls this once, wherever its sheets go; what it
 * shows comes from the [MomentNameTarget]:
 *
 * - a chapter: "PART 3 OF 9", "Name this chapter", its span and length;
 * - a new mark: "NEW MOMENT · 24:13", "Name this moment", and the nudges.
 *
 * [picture] draws the frame for the open target (its `frameMs`); the player
 * passes none for a new mark, whose picture is the paused film above.
 */
@Composable
fun MomentNameSheetHost(namer: MomentNamer, picture: (@Composable (MomentNameTarget) -> Unit)? = null, onNudged: (Long) -> Unit = {}) {
    val naming by namer.naming.collectAsStateWithLifecycle()
    val suggestions by namer.suggestions.collectAsStateWithLifecycle()
    val open = naming ?: return
    val target = open.target
    MomentNameSheet(
        eyebrow = if (target.isNew) "New moment · ${formatClock(target.atMs)}" else target.part ?: formatClock(target.atMs),
        title = if (target.isNew) "Name this moment" else "Name this chapter",
        meta = when {
            target.isNew -> "Paused here until you save or cancel"
            target.endMs != null && target.endMs > target.atMs ->
                "${formatClock(target.atMs)} – ${formatClock(target.endMs)} · ${formatDurationShort(target.endMs - target.atMs)}"
            else -> null
        },
        name = open.text,
        placeholder = target.placeholder,
        suggestions = suggestions,
        onNameChange = namer::type,
        onPick = { namer.save(it) },
        onSave = { namer.save() },
        onDismiss = namer::cancel,
        picture = picture?.takeIf { target.frameMs != null }?.let { draw -> { draw(target) } },
        onNudge = if (target.isNew) { delta -> namer.nudge(delta)?.let(onNudged) } else null,
        saving = open.saving,
    )
}
