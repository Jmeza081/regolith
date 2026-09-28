package com.regolith.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.regolith.R
import com.regolith.ui.theme.BoxShape
import com.regolith.ui.theme.RegolithTheme
import com.regolith.ui.theme.Spacing
import com.regolith.ui.theme.TextStyles
import com.regolith.ui.theme.scaledDp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * One folder offered as a destination.
 *
 * [enabled] is false for a folder that is itself being moved, or that sits
 * inside one: a folder cannot become its own parent. The row stays visible
 * with [meta] saying why, because a destination that silently vanished
 * would read as a missing folder rather than a refused one.
 */
data class MoveChild(
    val folderId: Long,
    val name: String,
    val meta: String?,
    val enabled: Boolean = true,
)

/**
 * Everything the move sheet draws, computed by the ViewModel that owns the
 * walk. [chosenFolderId] is either the folder being looked at or one of its
 * [children]: the row picks, the chevron walks in — the same split Browse
 * uses while selecting, and for the same reason. Tapping a row that is
 * already chosen would otherwise be the only way to un-choose it, and there
 * is no such thing as no destination.
 */
data class MoveSheetState(
    /** "4 videos", "1 folder", "5 items" — what is being moved, in the title. */
    val itemsLabel: String,
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
    /**
     * Something went wrong while the sheet was open — a folder that could
     * not be made.
     *
     * It is carried HERE rather than sent to the snackbar because the
     * snackbar draws at the foot of the screen, which is where this sheet
     * is: the message would be delivered underneath the thing the user is
     * looking at. A message nobody can see is the same as no message.
     */
    val error: String? = null,
)

/**
 * "Move 4 videos": pick a folder ON THE SAME SHARE.
 *
 * Scoped to one share deliberately, and the subtitle says so. A rename
 * cannot cross shares — the server answers "cannot rename between
 * different trees" — so a picker that offered another share would be
 * promising a copy-then-delete, which is the shape that loses files.
 *
 * [onNewFolder] makes the destination that does not exist yet. It is first
 * in the list rather than last because it is the answer to the question the
 * sheet just asked — "where?" — for anyone who is here to tidy up. The new
 * folder is created immediately and chosen, so the move lands in it.
 *
 * Only the folders scroll. Where the walk is (and the way back up) stays at
 * the top, and the "Move to …" button stays at the bottom: a folder with a
 * hundred subfolders used to mean picking one near the top, then scrolling
 * the whole list to reach the button under it. Past [RAIL_AFTER] folders an
 * [AlphabetRail] runs down the edge, and the first folder under each letter
 * it jumps to is nudged, so the eye lands on it — the one that matters when
 * the list is too near its end to bring that folder to the top.
 */
@Composable
fun MoveToSheet(
    state: MoveSheetState,
    onUp: () -> Unit,
    onOpen: (folderId: Long) -> Unit,
    onChoose: (folderId: Long) -> Unit,
    onNewFolder: () -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = RegolithTheme.colors
    // One scroll position per folder walked into, so a folder opens at its
    // top rather than wherever the last one was left. Saveable, so a
    // rotation keeps the place (Android rebuilds the screen on rotation;
    // plain `remember` would forget it).
    val listState = rememberSaveable(state.currentFolderId, saver = LazyListState.Saver) { LazyListState() }
    val scope = rememberCoroutineScope()
    val letters = remember(state.children) { AlphabetIndex(state.children.map { it.name }) }
    // Room for the rail is decided by the list alone, not by whether it turns
    // out to scroll: deciding after the first layout would shift every row
    // sideways one frame after the folder opened.
    val railRoom = state.children.size > RAIL_AFTER && letters.present.size > 1
    // The folder a jump landed on, and a count that makes jumping to the
    // same letter twice a new nudge rather than no change.
    var nudged by remember { mutableStateOf<Long?>(null) }
    var nudges by remember { mutableIntStateOf(0) }
    LaunchedEffect(nudges) {
        // Forgotten once it has played: rows come and go as the list
        // scrolls, and a row scrolled back into view must not nudge again.
        delay(NUDGE_FORGET_MS)
        nudged = null
    }

    RegolithSheet(
        title = "Move ${state.itemsLabel}",
        subtitle = "Pick a folder on ${state.shareName}. A move can't leave the share it started on.",
        onDismiss = onDismiss,
        testTag = "move_sheet",
        contentScrolls = false,
        footer = {
            Box(Modifier.fillMaxWidth().height(1.dp).background(colors.hairline))
            if (state.error != null) {
                Spacer(Modifier.height(Spacing.s12))
                Text(
                    state.error,
                    style = TextStyles.meta,
                    color = colors.accent,
                    modifier = Modifier.fillMaxWidth().testTag("move_sheet_error"),
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
                "Each one moves in a single step on the server — nothing is copied, so nothing can be left " +
                    "half-moved. A folder takes everything inside it.",
                style = TextStyles.meta,
                color = colors.metadata,
                modifier = Modifier.fillMaxWidth().testTag("move_sheet_note"),
            )
        },
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

        Box(Modifier.weight(1f, fill = false)) {
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(end = if (railRoom) AlphabetRailWidth - SheetGutter else 0.dp),
                modifier = Modifier.fillMaxWidth().testTag("move_sheet_list"),
            ) {
                // The folder being looked at is itself a destination — that
                // is what "move it here" means once you have walked somewhere.
                item(key = "here") {
                    DestinationRow(
                        name = state.breadcrumb.substringAfterLast(" / "),
                        meta = if (state.currentChoosable) null else state.note,
                        chosen = state.chosenFolderId == state.currentFolderId,
                        enabled = state.currentChoosable,
                        onChoose = { onChoose(state.currentFolderId) },
                        onOpen = null,
                        testTag = "move_sheet_here",
                    )
                }
                item(key = "new_folder") {
                    Box(Modifier.fillMaxWidth().height(1.dp).background(colors.hairline))
                    NewFolderRow(onNewFolder)
                }
                items(state.children, key = { it.folderId }) { child ->
                    Box(Modifier.fillMaxWidth().height(1.dp).background(colors.hairline))
                    DestinationRow(
                        name = child.name,
                        meta = child.meta,
                        chosen = state.chosenFolderId == child.folderId,
                        enabled = child.enabled,
                        onChoose = { onChoose(child.folderId) },
                        // Still walkable when it cannot be chosen: a folder being
                        // moved may hold the folder you actually want.
                        onOpen = { onOpen(child.folderId) },
                        testTag = "move_sheet_folder_${child.folderId}",
                        nudge = if (child.folderId == nudged) nudges else 0,
                    )
                }
            }
            // Only once the list really scrolls: a rail beside rows that are
            // all on screen already would be a control with nothing to do.
            if (railRoom && (listState.canScrollForward || listState.canScrollBackward)) {
                AlphabetRail(
                    index = letters,
                    onJump = { _, position ->
                        // A jump, not a glide: the finger can cross five
                        // letters in the time a smooth scroll takes to do one.
                        scope.launch { listState.scrollToItem(LEAD_ROWS + position) }
                        nudged = state.children[position].folderId
                        nudges++
                    },
                    testTag = "move_sheet_rail",
                    // The list's area, plus the gutter on the end side: the
                    // strip belongs at the sheet's edge, where the thumb is.
                    modifier = Modifier.matchParentSize().bleedEnd(SheetGutter),
                )
            }
        }
    }
}

/** "New folder": made in the folder being looked at, and chosen. */
@Composable
private fun NewFolderRow(onNewFolder: () -> Unit) {
    val colors = RegolithTheme.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 64.dp)
            .clickable(interactionSource = null, indication = null, onClick = onNewFolder)
            .testTag("move_sheet_new_folder"),
    ) {
        Box(Modifier.size(34.scaledDp()).background(colors.badgeBg, BoxShape), contentAlignment = Alignment.Center) {
            Icon(
                painterResource(R.drawable.rg_ic_folder_plus),
                contentDescription = null,
                tint = colors.accent,
                modifier = Modifier.size(17.scaledDp()),
            )
        }
        Spacer(Modifier.width(Spacing.s12))
        Column(Modifier.weight(1f)) {
            Text("New folder", style = TextStyles.settingLabel, color = colors.ink, maxLines = 1)
            Text(
                "Made here, and chosen",
                style = TextStyles.meta,
                color = colors.metadata,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * One line in the sheet: the row chooses it, the chevron walks into it.
 * [onOpen] null means there is nowhere to walk (the folder you are in).
 *
 * [nudge] above zero plays the nudge — out toward the rail and back with a
 * bounce — and a new value plays it again. Zero settles the row at rest.
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
    nudge: Int = 0,
) {
    val colors = RegolithTheme.colors
    val shove = remember { Animatable(0f) }
    // Toward the rail, which is on the END side: the left in right-to-left.
    val towardRail = if (LocalLayoutDirection.current == LayoutDirection.Rtl) -1f else 1f
    LaunchedEffect(nudge) {
        if (nudge == 0) {
            shove.animateTo(0f)
        } else {
            shove.animateTo(1f, tween(NUDGE_OUT_MS, easing = FastOutSlowInEasing))
            shove.animateTo(0f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow))
        }
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 64.dp)
            // Read in the draw phase: the nudge moves the row without
            // recomposing it, frame after frame.
            .graphicsLayer { translationX = shove.value * towardRail * NudgeDistance.toPx() }
            .testTag(testTag),
    ) {
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

/**
 * Lays this out [by] wider than it is offered, reaching past its end edge
 * into the sheet's gutter, while telling its parent it is exactly the width
 * it was given — so nothing around it moves. Placed with `placeRelative`,
 * so in a right-to-left layout it reaches left, which is the end there.
 */
private fun Modifier.bleedEnd(by: Dp) = layout { measurable, constraints ->
    if (!constraints.hasBoundedWidth) {
        val placeable = measurable.measure(constraints)
        return@layout layout(placeable.width, placeable.height) { placeable.placeRelative(0, 0) }
    }
    val extra = by.roundToPx()
    val placeable = measurable.measure(constraints.copy(minWidth = constraints.minWidth + extra, maxWidth = constraints.maxWidth + extra))
    layout(placeable.width - extra, placeable.height) { placeable.placeRelative(0, 0) }
}

/** More folders than this, and the list gets an [AlphabetRail]: the owner's line between a list you read and one you hunt through. */
internal const val RAIL_AFTER = 10

/** The rows above the first folder: "here" and "New folder". A rail position is a folder's; the list's is this much further down. */
private const val LEAD_ROWS = 2

/** How far a nudged row travels before it springs back. */
private val NudgeDistance = 12.dp

private const val NUDGE_OUT_MS = 110

/** Long enough for the spring to settle; after that the nudge is history. */
private const val NUDGE_FORGET_MS = 900L
