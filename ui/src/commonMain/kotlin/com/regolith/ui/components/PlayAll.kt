package com.regolith.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.regolith.ui.theme.RegolithTheme
import com.regolith.ui.theme.SheetShape
import com.regolith.ui.theme.Spacing
import com.regolith.ui.theme.TextStyles
import com.regolith.ui.theme.designSp
import com.regolith.ui.theme.scaledDp
import com.regolith.ui.util.formatDurationShort

/**
 * "Play all" at the top of a collection: the one filled red on that screen.
 *
 * Only drawn where "all" means something you can see — inside a Library
 * collection or a Browse folder that holds files. Never on the Library root
 * or the share list, where all would mean the whole NAS.
 *
 * Web analogy: the primary action button on a playlist page.
 */
@Composable
fun PlayAllButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    PrimaryButton(
        text = "Play all",
        onClick = onClick,
        compact = true,
        leadingIcon = rememberVectorPainter(RegolithIcons.Play),
        testTag = "play_all_button",
        modifier = modifier.fillMaxWidth(),
    )
}

/**
 * The drawer [PlayAllButton] opens: how you want the queue ordered. Built
 * like the Library's sort sheet — same handle, same 22dp top radius, same
 * 48dp rows — because it is the same kind of question.
 *
 * [fileCount] and [totalMs] describe what is about to be queued; [firstName]
 * is the file "In order" would start with, so the choice is concrete rather
 * than abstract. [onPlay] is called with true for shuffle.
 */
@Composable
fun PlayAllSheet(
    fileCount: Int,
    totalMs: Long?,
    firstName: String?,
    onPlay: (shuffle: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    RegolithSheet(
        title = "Play all",
        subtitle = listOfNotNull(
            "$fileCount file" + if (fileCount == 1) "" else "s",
            totalMs?.takeIf { it > 0 }?.let { formatDurationShort(it) },
        ).joinToString(" · "),
        onDismiss = onDismiss,
        testTag = "play_all_sheet",
    ) {
        SheetChoice(
            icon = RegolithIcons.Play,
            label = "In order",
            note = firstName?.let { "Starts with $it." },
            testTag = "play_all_in_order",
            onClick = { onPlay(false) },
        )
        SheetChoice(
            icon = RegolithIcons.Shuffle,
            label = "Shuffle",
            note = "All $fileCount in a random order.",
            testTag = "play_all_shuffle",
            onClick = { onPlay(true) },
        )
    }
}

/** One row of the drawer: a 18dp glyph, the choice, and a line saying what it will do. */
@Composable
private fun SheetChoice(icon: ImageVector, label: String, note: String?, testTag: String, onClick: () -> Unit) {
    val colors = RegolithTheme.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 56.dp)
            .clickable(interactionSource = null, indication = null, onClick = onClick)
            .testTag(testTag),
    ) {
        Icon(icon, contentDescription = null, tint = colors.ink, modifier = Modifier.size(18.scaledDp()))
        Spacer(Modifier.width(Spacing.s12))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.s2)) {
            Text(label, style = TextStyles.settingLabel.copy(lineHeight = 20.designSp()), color = colors.ink)
            if (note != null) {
                Text(note, style = TextStyles.settingMeta, color = colors.metadata, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
