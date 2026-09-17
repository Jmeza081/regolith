package com.regolith.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.regolith.R
import com.regolith.ui.theme.BoxShape
import com.regolith.ui.theme.RegolithTheme
import com.regolith.ui.theme.Spacing
import com.regolith.ui.theme.TextStyles
import com.regolith.ui.theme.scaledDp

/** One folder offered as a destination. */
data class MoveChild(val folderId: Long, val name: String, val meta: String?)

/**
 * Everything the move sheet draws, computed by the ViewModel that owns the
 * walk. [chosenFolderId] is either the folder being looked at or one of its
 * [children]: the row picks, the chevron walks in — the same split Browse
 * uses while selecting, and for the same reason. Tapping a row that is
 * already chosen would otherwise be the only way to un-choose it, and there
 * is no such thing as no destination.
 */
data class MoveSheetState(
    val videoCount: Int,
    val shareName: String,
    /** "media / Films" — where the walk currently is. */
    val breadcrumb: String,
    val currentFolderId: Long,
    val children: List<MoveChild> = emptyList(),
    val chosenFolderId: Long,
    val chosenName: String,
    val canUp: Boolean = false,
    /** False when the folder being looked at is the one the files are already in. */
    val currentChoosable: Boolean = true,
    /** False when the destination is where the files already are. */
    val confirmEnabled: Boolean = true,
    /** Why it is off, or null. */
    val note: String? = null,
)

/**
 * "Move 4 videos": pick a folder ON THE SAME SHARE.
 *
 * Scoped to one share deliberately, and the subtitle says so. A rename
 * cannot cross shares — the server answers "cannot rename between
 * different trees" — so a picker that offered another share would be
 * promising a copy-then-delete, which is the shape that loses files.
 */
@Composable
fun MoveToSheet(
    state: MoveSheetState,
    onUp: () -> Unit,
    onOpen: (folderId: Long) -> Unit,
    onChoose: (folderId: Long) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = RegolithTheme.colors
    RegolithSheet(
        title = if (state.videoCount == 1) "Move 1 video" else "Move ${state.videoCount} videos",
        subtitle = "Pick a folder on ${state.shareName}. A move can't leave the share it started on.",
        onDismiss = onDismiss,
        testTag = "move_sheet",
    ) {
        Spacer(Modifier.height(Spacing.s8))

        // Where the walk is, and the way back up.
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp)) {
            if (state.canUp) {
                Box(
                    Modifier.size(44.dp).clickable(interactionSource = null, indication = null, onClick = onUp).testTag("move_sheet_up"),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Icon(painterResource(R.drawable.rg_ic_chevron_left), contentDescription = "Up one folder", tint = colors.inkSoft, modifier = Modifier.size(19.scaledDp()))
                }
            }
            Text(
                state.breadcrumb,
                style = TextStyles.settingLabel,
                color = colors.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).testTag("move_sheet_breadcrumb"),
            )
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.hairline))

        // The folder being looked at is itself a destination — that is what
        // "move it here" means once you have walked somewhere.
        DestinationRow(
            name = state.breadcrumb.substringAfterLast(" / "),
            meta = if (state.currentChoosable) null else state.note,
            chosen = state.chosenFolderId == state.currentFolderId,
            enabled = state.currentChoosable,
            onChoose = { onChoose(state.currentFolderId) },
            onOpen = null,
            testTag = "move_sheet_here",
        )

        state.children.forEach { child ->
            Box(Modifier.fillMaxWidth().height(1.dp).background(colors.hairline))
            DestinationRow(
                name = child.name,
                meta = child.meta,
                chosen = state.chosenFolderId == child.folderId,
                enabled = true,
                onChoose = { onChoose(child.folderId) },
                onOpen = { onOpen(child.folderId) },
                testTag = "move_sheet_folder_${child.folderId}",
            )
        }

        Spacer(Modifier.height(Spacing.s18))
        PrimaryButton(
            text = "Move to ${state.chosenName}",
            onClick = onConfirm,
            enabled = state.confirmEnabled,
            testTag = "move_sheet_confirm",
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(Spacing.s12))
        Text(
            "Each video moves in one step on the server — nothing is copied, so no file can be left half-moved.",
            style = TextStyles.meta,
            color = colors.metadata,
            modifier = Modifier.fillMaxWidth().testTag("move_sheet_note"),
        )
    }
}

/**
 * One line in the sheet: the row chooses it, the chevron walks into it.
 * [onOpen] null means there is nowhere to walk (the folder you are in).
 */
@Composable
private fun DestinationRow(
    name: String,
    meta: String?,
    chosen: Boolean,
    enabled: Boolean,
    onChoose: () -> Unit,
    onOpen: (() -> Unit)?,
    testTag: String,
) {
    val colors = RegolithTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 64.dp).testTag(testTag)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f)
                .clickable(interactionSource = null, indication = null, enabled = enabled, onClick = onChoose)
                .testTag("${testTag}_choose"),
        ) {
            Box(Modifier.size(34.scaledDp()).background(colors.badgeBg, BoxShape), contentAlignment = Alignment.Center) {
                Icon(
                    painterResource(R.drawable.rg_ic_browse),
                    contentDescription = null,
                    tint = if (enabled) colors.ink else colors.disabledInk,
                    modifier = Modifier.size(17.scaledDp()),
                )
            }
            Spacer(Modifier.width(Spacing.s12))
            Column(Modifier.weight(1f)) {
                Text(
                    name,
                    style = TextStyles.settingLabel,
                    color = if (enabled) colors.ink else colors.disabledInk,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (meta != null) Text(meta, style = TextStyles.meta, color = colors.metadata, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (chosen) {
                Icon(
                    painterResource(R.drawable.rg_ic_check),
                    contentDescription = "Chosen",
                    tint = colors.accent,
                    modifier = Modifier.padding(horizontal = Spacing.s8).size(18.scaledDp()),
                )
            }
        }
        if (onOpen != null) {
            Box(
                Modifier.size(44.dp).clickable(interactionSource = null, indication = null, onClick = onOpen).testTag("${testTag}_open"),
                contentAlignment = Alignment.Center,
            ) {
                Icon(painterResource(R.drawable.rg_ic_chevron_right), contentDescription = "Open $name", tint = colors.metadata, modifier = Modifier.size(15.scaledDp()))
            }
        }
    }
}
