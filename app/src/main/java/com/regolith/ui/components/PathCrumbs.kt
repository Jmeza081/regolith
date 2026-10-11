package com.regolith.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.regolith.ui.theme.RegolithTheme
import com.regolith.ui.theme.Spacing
import com.regolith.ui.theme.TextStyles

/**
 * One step of a folder path shown as text: what it says, and the folder a
 * tap on it opens. [folderId] is null for a step the library has no folder
 * for, such as a phone path's "DCIM", which is only part of an album's name.
 */
@Immutable
data class PathCrumb(val label: String, val folderId: Long?)

/**
 * A folder path whose steps can be tapped, as breadcrumbs: Title Detail's
 * Path line, "/media/Films/Arrival/", where each name opens its folder.
 * It reads as the path it always did. The names are in ink over a dotted
 * underline, and the slashes grey, which is what says the names can be
 * tapped; a step with no folder stays grey like the slashes, with no dots.
 *
 * Lines up at the end and wraps from there, as a fact row's value does, and
 * a name too long for a line wraps inside itself. [leadingSlash] is for a
 * share's path ("/media/…"); a phone's ("DCIM/Camera/") starts at its first
 * name. Each tappable name is tagged `<testTag>_<index>`.
 *
 * Web analogy: a breadcrumb `<nav>` of links, styled to read as a path.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PathCrumbs(
    crumbs: List<PathCrumb>,
    onOpen: (index: Int, crumb: PathCrumb) -> Unit,
    testTag: String,
    modifier: Modifier = Modifier,
    leadingSlash: Boolean = true,
    style: TextStyle = TextStyles.factValue,
) {
    val colors = RegolithTheme.colors
    // The slashes' grey: a dotted line has less ink than a solid one, so it
    // takes a lighter colour to read as plainly.
    val underline = colors.metadata
    // The same height for every piece, slash or name, so they share a line
    // the way the letters of one string do; the padding is the names' touch
    // target, which a fact row has the room for.
    val piece = Modifier.padding(vertical = Spacing.s8)
    @Composable
    fun Slash() = Text("/", style = style, color = colors.metadata, modifier = piece.padding(horizontal = 1.dp))
    FlowRow(
        modifier = modifier.testTag(testTag),
        horizontalArrangement = Arrangement.End,
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        if (leadingSlash) Slash()
        crumbs.forEachIndexed { index, crumb ->
            if (crumb.folderId != null) {
                Text(
                    crumb.label,
                    style = style,
                    color = colors.ink,
                    modifier = Modifier
                        .clickable(interactionSource = null, indication = null, role = Role.Button, onClickLabel = "Open folder") { onOpen(index, crumb) }
                        .then(piece)
                        // A link's underline, dotted, just under the letters. Each
                        // dot is a dash with no length and round ends.
                        .drawBehind {
                            val dot = DOT_SIZE.toPx()
                            val y = size.height + dot
                            drawLine(
                                underline,
                                Offset(dot / 2, y),
                                Offset(size.width - dot / 2, y),
                                strokeWidth = dot,
                                cap = StrokeCap.Round,
                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(0.01f, DOT_PITCH.toPx())),
                            )
                        }
                        .testTag("${testTag}_$index"),
                )
            } else {
                Text(crumb.label, style = style, color = colors.metadata, modifier = piece)
            }
            Slash()
        }
    }
}

/** How big an underline's dots are, and how far apart their centres. */
private val DOT_SIZE = 1.5.dp
private val DOT_PITCH = 3.5.dp
