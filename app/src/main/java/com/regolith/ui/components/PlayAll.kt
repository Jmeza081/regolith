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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.regolith.R
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
        leadingIcon = painterResource(R.drawable.rg_ic_play),
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
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayAllSheet(
    fileCount: Int,
    totalMs: Long?,
    firstName: String?,
    onPlay: (shuffle: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = RegolithTheme.colors
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = SheetShape,
        containerColor = colors.surface,
        contentColor = colors.ink,
        dragHandle = null,
    ) {
        Column(
            Modifier.padding(start = Spacing.s18, end = Spacing.s18, top = Spacing.s12)
                .navigationBarsPadding().testTag("play_all_sheet"),
        ) {
            Box(Modifier.align(Alignment.CenterHorizontally).size(38.dp, 4.dp).background(colors.raised, RoundedCornerShape(2.dp)))
            Spacer(Modifier.height(Spacing.s12))
            DisplayText("Play all", style = TextStyles.dialogTitle.copy(fontSize = 14.designSp(), lineHeight = 19.6.designSp()))
            Text(
                listOfNotNull(
                    "$fileCount file" + if (fileCount == 1) "" else "s",
                    totalMs?.takeIf { it > 0 }?.let { formatDurationShort(it) },
                ).joinToString(" · "),
                style = TextStyles.meta12,
                color = colors.metadata,
                modifier = Modifier.padding(top = Spacing.s2).testTag("play_all_meta"),
            )
            Spacer(Modifier.height(Spacing.s8))
            SheetChoice(
                icon = R.drawable.rg_ic_play,
                label = "In order",
                note = firstName?.let { "Starts with $it." },
                testTag = "play_all_in_order",
                onClick = { onPlay(false) },
            )
            SheetChoice(
                icon = R.drawable.rg_ic_shuffle,
                label = "Shuffle",
                note = "All $fileCount in a random order.",
                testTag = "play_all_shuffle",
                onClick = { onPlay(true) },
            )
            Spacer(Modifier.height(Spacing.s12))
        }
    }
}

/** One row of the drawer: a 18dp glyph, the choice, and a line saying what it will do. */
@Composable
private fun SheetChoice(icon: Int, label: String, note: String?, testTag: String, onClick: () -> Unit) {
    val colors = RegolithTheme.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 56.dp)
            .clickable(interactionSource = null, indication = null, onClick = onClick)
            .testTag(testTag),
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = colors.ink, modifier = Modifier.size(18.scaledDp()))
        Spacer(Modifier.width(Spacing.s12))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.s2)) {
            Text(label, style = TextStyles.settingLabel.copy(lineHeight = 20.designSp()), color = colors.ink)
            if (note != null) {
                Text(note, style = TextStyles.settingMeta, color = colors.metadata, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
