package com.regolith.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.R as LucideR
import com.regolith.ui.theme.RegolithTheme
import com.regolith.ui.theme.Spacing
import com.regolith.ui.theme.TextStyles
import com.regolith.ui.theme.ThumbShape

/**
 * The quick name sheet: name one moment without opening the chapter editor.
 * Its header is the moment's [picture] beside what it is ([eyebrow],
 * [title], [meta]); under it the name field and the library's names as
 * chips. **A chip is the whole job:** tapping one saves that name and the
 * sheet closes ([onPick]). Typing narrows the chips, and Save (or the
 * keyboard's Done) saves what was typed.
 *
 * Opened from a chapter's dashed name on the video page and in the player's
 * Chapters panel ([NameItPill]), and by the player's Mark pill, which passes
 * [onNudge] for its ±1 s / ±5 s row, since a mark is usually tapped a beat
 * late. Most callers reach this through `ui.moments.MomentNameSheet`, which
 * fills it from a `MomentNamer`.
 */
@Composable
fun MomentNameSheet(
    eyebrow: String,
    title: String,
    meta: String?,
    name: String,
    placeholder: String,
    suggestions: List<String>,
    onNameChange: (String) -> Unit,
    onPick: (String) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
    /** The moment's frame, drawn 128×72 at the left of the header; null for none. */
    picture: (@Composable () -> Unit)? = null,
    /** Moves a new mark by the given milliseconds; null hides the nudge row. */
    onNudge: ((Long) -> Unit)? = null,
    /** Save is writing to the share: ring on the button, the chips wait. */
    saving: Boolean = false,
    testTag: String = "moment_name_sheet",
) {
    val colors = RegolithTheme.colors
    RegolithSheet(
        title = title,
        onDismiss = onDismiss,
        testTag = testTag,
        header = {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s12), verticalAlignment = Alignment.CenterVertically) {
                if (picture != null) {
                    Box(Modifier.width(128.dp).height(72.dp).clip(ThumbShape).background(colors.skeleton)) { picture() }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.s4)) {
                    Eyebrow(eyebrow, muted = true)
                    Text(title, style = TextStyles.rowLabel, color = colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (meta != null) Text(meta, style = TextStyles.meta12, color = colors.metadata, maxLines = 1)
                }
            }
            Spacer(Modifier.height(Spacing.s12))
        },
        footer = {
            Row(
                Modifier.fillMaxWidth().padding(top = Spacing.s12),
                horizontalArrangement = Arrangement.spacedBy(Spacing.s8),
            ) {
                SecondaryButton("Cancel", onDismiss, "moment_name_cancel_button", Modifier.weight(1f), enabled = !saving)
                PrimaryButton("Save", onSave, "moment_name_save_button", Modifier.weight(1f), loading = saving)
            }
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.s12), modifier = Modifier.padding(top = Spacing.s8)) {
            if (onNudge != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s8)) {
                    listOf(-5_000L to "−5 s", -1_000L to "−1 s", 1_000L to "+1 s", 5_000L to "+5 s").forEach { (delta, label) ->
                        PillButton(
                            text = label, onClick = { onNudge(delta) }, onMedia = false, fill = true,
                            testTag = "moment_name_nudge_${if (delta < 0) "back" else "on"}_${kotlin.math.abs(delta) / 1000}",
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
            RegolithTextField(
                value = name, onValueChange = onNameChange,
                label = "Name", placeholder = placeholder, testTag = "moment_name_field",
                enabled = !saving,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onSave() }),
            )
            NameSuggestionChips(
                suggestions = suggestions,
                onPick = { if (!saving) onPick(it) },
                hint = "Used elsewhere in your library · tap to save",
                testTag = "moment_name_suggestions",
            )
        }
    }
}

/**
 * Names the library already uses, as one scrolling row of chips under a
 * name field: the chapter editor's and the quick name sheet's.
 *
 * A moment is only a filter in Search if it is spelled the same way twice,
 * and nothing used to show you what you called it last time — so the same
 * scene became "The heist", "the heist" and "Heist", three chips that each
 * found a third of the films. Tapping a chip writes that spelling exactly.
 *
 * One scrolling row rather than a wrapping block: a block that grows by a
 * line as you type pushes the fields under your thumb around. Draws nothing
 * when there are no names. Each chip is tagged `<testTag>_<name>`, lower
 * case, spaces as underscores.
 */
@Composable
fun NameSuggestionChips(
    suggestions: List<String>,
    onPick: (String) -> Unit,
    testTag: String,
    hint: String = "Used elsewhere in your library",
) {
    if (suggestions.isEmpty()) return
    val colors = RegolithTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.s4), modifier = Modifier.testTag(testTag)) {
        Text(hint, style = TextStyles.meta, color = colors.metadata)
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(Spacing.s8),
        ) {
            suggestions.forEach { name ->
                PillButton(
                    text = name,
                    onClick = { onPick(name) },
                    testTag = "${testTag}_${name.lowercase().replace(' ', '_')}",
                    onMedia = false,
                )
            }
        }
    }
}

/**
 * An unnamed chapter's name, drawn as a dashed pill with a pencil: "this
 * one has no name yet, tap to give it one". Sits where the chapter's name
 * would be, under its picture, on the video page and in the player's
 * Chapters panel; the tap opens [MomentNameSheet]. The picture above it
 * still plays from the chapter, as a named one does.
 *
 * The touch target is 40dp tall around a 30dp pill, so a tile carrying one
 * stands a little taller than a named tile beside it.
 */
@Composable
fun NameItPill(label: String, onClick: () -> Unit, testTag: String, modifier: Modifier = Modifier) {
    val colors = RegolithTheme.colors
    Box(
        modifier.defaultMinSize(minHeight = 40.dp)
            .clickable(interactionSource = null, indication = null, onClick = onClick)
            .testTag(testTag),
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(
            Modifier.height(30.dp)
                .drawBehind {
                    val width = 1.dp.toPx()
                    drawRoundRect(
                        color = colors.metadata,
                        cornerRadius = CornerRadius(size.height / 2),
                        style = Stroke(width = width, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))),
                        topLeft = androidx.compose.ui.geometry.Offset(width / 2, width / 2),
                        size = androidx.compose.ui.geometry.Size(size.width - width, size.height - width),
                    )
                }
                .padding(start = Spacing.s8, end = Spacing.s12),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(painterResource(LucideR.drawable.lucide_ic_pencil), contentDescription = null, tint = colors.body, modifier = Modifier.size(12.dp))
            Text(label, style = TextStyles.meta, color = colors.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
